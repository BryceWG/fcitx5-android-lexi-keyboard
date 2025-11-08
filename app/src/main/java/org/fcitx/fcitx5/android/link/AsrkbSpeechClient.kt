/*
 * Minimal external AIDL client to link with Yanxi (asr-keyboard)
 * for connectivity test via vendorId = "mock".
 */
package org.fcitx.fcitx5.android.link

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.brycewg.asrkb.aidl.IExternalSpeechService
import com.brycewg.asrkb.aidl.ISpeechCallback
import com.brycewg.asrkb.aidl.SpeechConfig
import org.fcitx.fcitx5.android.input.FcitxInputMethodService

object AsrkbSpeechClient {
    private const val TAG = "AsrkbLink"
    private var bound = false
    private var connection: ServiceConnection? = null

    fun startMockSession(service: FcitxInputMethodService) {
        if (bound) return
        val ctx = service
        val intent = Intent().apply {
            component = ComponentName(
                "com.brycewg.asrkb",
                "com.brycewg.asrkb.api.ExternalSpeechService"
            )
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    val api = IExternalSpeechService.Stub.asInterface(binder)
                    if (api == null) {
                        toast(ctx, "未连接到言犀服务")
                        unbind(ctx)
                        return
                    }
                    val cb = object : ISpeechCallback.Stub() {
                        override fun onState(sessionId: Int, state: Int, message: String?) { /* ignore for mock */ }
                        override fun onPartial(sessionId: Int, text: String?) { /* ignore for mock */ }
                        override fun onFinal(sessionId: Int, text: String?) {
                            val t = text ?: return
                            service.lifecycleScope.launch {
                                service.commitText(t)
                            }
                            unbind(ctx)
                        }
                        override fun onError(sessionId: Int, code: Int, message: String?) {
                            toast(ctx, "语音服务错误: $code")
                            unbind(ctx)
                        }
                        override fun onAmplitude(sessionId: Int, amplitude: Float) { /* ignore */ }
                    }
                    val cfg = SpeechConfig(vendorId = "mock")
                    val sid = api.startSession(cfg, cb)
                    if (sid <= 0) {
                        toast(ctx, "无法启动会话: $sid")
                        unbind(ctx)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "bind/start failed", t)
                    toast(ctx, "无法连接言犀服务")
                    unbind(ctx)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                unbind(ctx)
            }
        }
        connection = conn
        try {
            bound = ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            if (!bound) toast(ctx, "未找到言犀服务")
        } catch (t: Throwable) {
            Log.w(TAG, "bind failed", t)
            toast(ctx, "绑定失败")
            unbind(ctx)
        }
    }

    private fun unbind(ctx: Context) {
        if (!bound) return
        try { ctx.unbindService(connection!!) } catch (_: Throwable) {}
        bound = false
        connection = null
    }

    private fun toast(ctx: Context, msg: String) {
        try {
            ContextCompat.getMainExecutor(ctx).execute {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) { }
    }
}

