package com.blibla.animeshimejipetscreen.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.blibla.animeshimejipetscreen.R
import com.blibla.animeshimejipetscreen.data.local.DbProvider
import com.blibla.animeshimejipetscreen.data.local.entity.ActiveShimejiEntity
import com.blibla.animeshimejipetscreen.data.prefs.UserPrefs
import com.blibla.animeshimejipetscreen.mascot.ShimejiEngineAnimator
import com.blibla.animeshimejipetscreen.mascot.ShimejiSpec
import com.blibla.animeshimejipetscreen.mascot.ShimejiSpecParser
import com.blibla.animeshimejipetscreen.mascot.BehaviorsParser
import com.blibla.animeshimejipetscreen.mascot.forAndroid
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.hypot
import kotlin.math.max
import com.blibla.animeshimejipetscreen.data.prefs.SlotPrefs
import kotlinx.coroutines.flow.first
import com.blibla.animeshimejipetscreen.data.prefs.ServiceHeartbeatPrefs

class ShimejiOverlayService : Service() {

    companion object {
        const val EXTRA_SHIMEJI_ID = "shimeji_id"
        const val EXTRA_SHIMEJI_NAME = "shimeji_name"
        const val EXTRA_SHIMEJI_ICON = "shimeji_icon"

        const val ACTION_ENABLE = "action_enable"

        const val ACTION_SHOW = "action_show"
        const val ACTION_STOP = "action_stop"         // stop all
        const val ACTION_STOP_ONE = "action_stop_one" // stop per id

        private const val CHANNEL_ID = "shimeji_overlay"
        private const val NOTIF_ID = 1001
    }

    private lateinit var windowManager: WindowManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class DragState(
        var prevTargetX: Int = 0,
        var prevTargetY: Int = 0
    )

    private data class Instance(
        val id: Long,
        val view: ImageView,
        val params: WindowManager.LayoutParams,
        val animator: ShimejiEngineAnimator,
        val settingsJob: Job,
        val drag: DragState = DragState(),
        var attached: Boolean = true
    )

    private val instances = mutableMapOf<Long, Instance>()

    private var isScreenPaused = false

    private var isScreenHidden = false

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenHidden = true
                    hideAllOverlays()
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    if (isScreenHidden) {
                        isScreenHidden = false
                        showAllOverlays()
                    }
                }
            }
        }
    }

    private var heartbeatJob: Job? = null

    private suspend fun getMaxSlotsToday(): Int {
        val prefs = SlotPrefs(this)
        prefs.resetDailyBonusIfNewDay()
        return prefs.openSlotsFlow.first()
    }

    private fun startHeartbeat() {
        val hb = ServiceHeartbeatPrefs(this)
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                hb.setLastAlive(System.currentTimeMillis())
                delay(5_000) // 5 detik cukup
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }


    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundSafe()
        startHeartbeat()

        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENABLE -> {
                startForegroundSafe()
                showAllFromDb()
                return START_STICKY
            }

            ACTION_STOP -> {
                stopAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_STOP_ONE -> {
                val id = intent.getLongExtra(EXTRA_SHIMEJI_ID, -1L)
                if (id > 0) stopOne(id)

                if (instances.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_SHOW, null -> {
                val id = intent?.getLongExtra(EXTRA_SHIMEJI_ID, -1L) ?: -1L
                val name = intent?.getStringExtra(EXTRA_SHIMEJI_NAME).orEmpty()
                val iconUrl = intent?.getStringExtra(EXTRA_SHIMEJI_ICON).orEmpty()

                serviceScope.launch {
                    val db = DbProvider.get(this@ShimejiOverlayService)
                    val limit = getMaxSlotsToday()
                    val count = withContext(Dispatchers.IO) { db.activeShimejiDao().countAll() }

                    if (count >= limit) return@launch

                    showAnimated(id, name, iconUrl)
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundSafe() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Shimeji Overlay", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Shimeji is running")
            .setContentText("Overlay active. Stop from app Settings.")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startSettingsRealtimeFor(anim: ShimejiEngineAnimator): Job {
        val prefs = UserPrefs(this)
        return serviceScope.launch {
            launch { prefs.animScale.collectLatest { anim.setScale(it) } }
            launch { prefs.animSpeed.collectLatest { anim.setSpeed(it) } }
        }
    }

    private fun showAllFromDb() {
        val db = DbProvider.get(this)
        serviceScope.launch(Dispatchers.IO) {
            val list = db.activeShimejiDao().getAllOnce() // kamu perlu query non-flow
            withContext(Dispatchers.Main.immediate) {
                list.forEach { s ->
                    showAnimated(s.id, s.name, s.iconUrl)
                }
            }
        }
    }

    private fun showAnimated(id: Long, name: String, iconUrl: String) {
        if (id <= 0) return
        if (instances.containsKey(id)) return // sudah aktif

        val shouldAttachNow = !isScreenHidden

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40 + (instances.size * 24) // biar tidak nabrak start position
            y = 200 + (instances.size * 24)
        }

        val iv = ImageView(this)

        // Local drag vars per view
        iv.setOnTouchListener(object : android.view.View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f

            private var lastMoveTime = 0L
            private var lastMoveX = 0
            private var lastMoveY = 0

            override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
                val inst = instances[id] ?: return false
                val animator = inst.animator
                val drag = inst.drag

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY

                        lastMoveTime = SystemClock.uptimeMillis()
                        lastMoveX = params.x
                        lastMoveY = params.y

                        animator.setGrabbed(true)
                        animator.setDragTarget(params.x, params.y)
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val nx = initialX + (event.rawX - touchX).toInt()
                        val ny = initialY + (event.rawY - touchY).toInt()

                        // follow the finger immediately (touch-rate), not just on the
                        // next animation tick, for a smooth 1:1 drag
                        animator.dragTo(nx, ny)

                        drag.prevTargetX = lastMoveX
                        drag.prevTargetY = lastMoveY
                        lastMoveX = nx
                        lastMoveY = ny
                        lastMoveTime = SystemClock.uptimeMillis()
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val now = SystemClock.uptimeMillis()
                        val dtMs = max(1L, now - lastMoveTime)

                        val dx = (lastMoveX - drag.prevTargetX).toFloat()
                        val dy = (lastMoveY - drag.prevTargetY).toFloat()

                        val vx = (dx / dtMs) * 1000f
                        val vy = (dy / dtMs) * 1000f
                        val speedAbs = hypot(vx.toDouble(), vy.toDouble()).toFloat()

                        animator.setDragTarget(lastMoveX, lastMoveY)
                        animator.setGrabbed(false)

                        val (px, _) = animator.getLastLayoutPosition()
                            ?: (params.x to params.y)
                        val (wNow, _) = animator.getLastLayoutSize()
                            ?: (max(1, params.width) to 0)

                        val sw = resources.displayMetrics.widthPixels
                        val edge = 56

                        val nearLeft = px <= edge
                        val nearRight = (px + wNow) >= (sw - edge)

                        if (nearLeft || nearRight) {
                            animator.stickToNearestWall()
                            return true
                        }

                        if (speedAbs > 900f) {
                            animator.throwWithVelocity(vx, vy)
                        }
                        return true
                    }
                }
                return false
            }
        })

        if (shouldAttachNow) {
            windowManager.addView(iv, params)
        }

        val extractedDir = File(filesDir, "shimeji/$id/extracted")
        val actionsFile = File(extractedDir, "actions.xml")

        if (!actionsFile.exists()) {
            // fallback image only (no animator)
            val fallback = File(extractedDir, "shime1.png")
            if (fallback.exists()) {
                iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(fallback.absolutePath))
            }
            // tetap kita simpan sebagai "aktif"? biasanya iya, tapi tanpa animator.
            // untuk sederhana: anggap aktif, tapi stopOne tetap bisa remove view.
            // bikin animator dummy? nggak perlu. Kita handle dengan remove langsung.
            instances[id] = Instance(
                id = id,
                view = iv,
                params = params,
                animator = ShimejiEngineAnimator(
                    imageView = iv,
                    windowManager = windowManager,
                    layoutParams = params,
                    extractedDir = extractedDir,
                    spec = ShimejiSpec(emptyMap(), emptyMap()),
                    behaviorEntries = emptyList()
                ),
                settingsJob = Job().apply { cancel() }, // no-op
                attached = shouldAttachNow
            )
            upsertActive(id, name, iconUrl)
            return
        }

        val spec = ShimejiSpecParser.parse(actionsFile).forAndroid()

        if (spec.actions.isEmpty()) {
            // no usable actions -> remove view biar tidak nyangkut
            try { windowManager.removeView(iv) } catch (_: Exception) {}
            return
        }

        // behaviors.xml dari pack kalau ada, kalau tidak pakai default bawaan app
        val behaviorsFile = File(extractedDir, "behaviors.xml")
        val behaviorEntries = try {
            if (behaviorsFile.exists()) {
                BehaviorsParser.parse(behaviorsFile)
            } else {
                assets.open("default_behaviors.xml").use { BehaviorsParser.parse(it) }
            }
        } catch (_: Exception) {
            emptyList()
        }

        val animator = ShimejiEngineAnimator(
            imageView = iv,
            windowManager = windowManager,
            layoutParams = params,
            extractedDir = extractedDir,
            spec = spec,
            behaviorEntries = behaviorEntries
        )

        val settingsJob = startSettingsRealtimeFor(animator)

        instances[id] = Instance(
            id = id,
            view = iv,
            params = params,
            animator = animator,
            settingsJob = settingsJob,
            attached = shouldAttachNow
        )

        animator.start()

        if (isScreenHidden) animator.pause()

        upsertActive(id, name, iconUrl)
    }

    private fun upsertActive(id: Long, name: String, iconUrl: String) {
        val db = DbProvider.get(this)
        serviceScope.launch(Dispatchers.IO) {
            db.activeShimejiDao().upsert(
                ActiveShimejiEntity(
                    id = id,
                    name = name.ifBlank { "Shimeji $id" },
                    iconUrl = iconUrl
                )
            )
        }
    }

    private fun deleteActive(id: Long) {
        val db = DbProvider.get(this)
        serviceScope.launch(Dispatchers.IO) {
            db.activeShimejiDao().deleteById(id)
        }
    }

    private fun clearActive() {
        val db = DbProvider.get(this)
        serviceScope.launch(Dispatchers.IO) {
            db.activeShimejiDao().clear()
        }
    }

    private fun stopOne(id: Long) {
        val inst = instances.remove(id)

        if (inst != null) {
            try { inst.settingsJob.cancel() } catch (_: Exception) {}
            try { inst.animator.destroy() } catch (_: Exception) {}
            if (inst.attached) {
                try { windowManager.removeView(inst.view) } catch (_: Exception) {}
            }
        }

        deleteActive(id) // ✅ selalu
    }


    private fun stopAll() {
        val ids = instances.keys.toList()
        ids.forEach { stopOne(it) }
        clearActive()
    }

    private fun hideAllOverlays() {
        instances.values.forEach { inst ->
            // pause loop dulu biar gak updateViewLayout saat view dilepas
            try { inst.animator.pause() } catch (_: Exception) {}

            if (inst.attached) {
                try { windowManager.removeViewImmediate(inst.view) } catch (_: Exception) {}
                inst.attached = false
            }
        }
    }

    private fun showAllOverlays() {
        instances.values.forEach { inst ->
            if (!inst.attached) {
                try { windowManager.addView(inst.view, inst.params) } catch (_: Exception) {}
                inst.attached = true
            }
            try { inst.animator.resume() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        stopHeartbeat()
        serviceScope.cancel()
        super.onDestroy()
    }


    override fun onBind(intent: Intent?): IBinder? = null
}
