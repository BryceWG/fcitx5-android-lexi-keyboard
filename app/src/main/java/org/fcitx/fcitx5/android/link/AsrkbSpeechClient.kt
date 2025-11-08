/*
 * Minimal external AIDL client to link with Yanxi (asr-keyboard)
 * for connectivity test via vendorId = "mock".
 */
package org.fcitx.fcitx5.android.link

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
                    val remote = binder ?: throw IllegalStateException("no binder")
                    // 准备回调 Binder：仅处理 onFinal，其余忽略
                    val cbBinder = object : Binder() {
                        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                            return try {
                                when (code) {
                                    CB_onState, CB_onPartial, CB_onAmplitude -> {
                                        reply?.writeNoException(); true
                                    }
                                    CB_onFinal -> {
                                        data.enforceInterface(DESCRIPTOR_CB)
                                        val sid = data.readInt()
                                        val text = data.readString() ?: ""
                                        service.lifecycleScope.launch { service.commitText(text) }
                                        reply?.writeNoException(); true
                                    }
                                    CB_onError -> {
                                        data.enforceInterface(DESCRIPTOR_CB)
                                        val sid = data.readInt()
                                        val codeVal = data.readInt()
                                        val msg = data.readString()
                                        toast(ctx, "语音服务错误: $codeVal")
                                        reply?.writeNoException(); true
                                    }
                                    IBinder.INTERFACE_TRANSACTION -> { reply?.writeString(DESCRIPTOR_CB); true }
                                    else -> super.onTransact(code, data, reply, flags)
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "callback transact handle failed", t)
                                false
                            }
                        }
                    }

                    // 调用 startSession(config, callback) via Binder transact
                    val cfg = SpeechConfig(vendorId = "mock")
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    var sid = -999
                    try {
                        data.writeInterfaceToken(DESCRIPTOR_SVC)
                        // write config as Parcelable (presence flag + parcel)
                        data.writeInt(1)
                        cfg.writeToParcel(data, 0)
                        // write callback binder
                        data.writeStrongBinder(cbBinder)
                        remote.transact(TRANSACTION_startSession, data, reply, 0)
                        reply.readException()
                        sid = reply.readInt()
                    } finally {
                        try { data.recycle() } catch (_: Throwable) {}
                        try { reply.recycle() } catch (_: Throwable) {}
                    }
                    if (sid <= 0) { toast(ctx, "无法启动会话: $sid"); unbind(ctx) }
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

    // 与服务端保持一致的接口描述符与事务号
    private const val DESCRIPTOR_SVC = "com.brycewg.asrkb.aidl.IExternalSpeechService"
    private const val TRANSACTION_startSession = IBinder.FIRST_CALL_TRANSACTION + 0

    private const val DESCRIPTOR_CB = "com.brycewg.asrkb.aidl.ISpeechCallback"
    private const val CB_onState = IBinder.FIRST_CALL_TRANSACTION + 0
    private const val CB_onPartial = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val CB_onFinal = IBinder.FIRST_CALL_TRANSACTION + 2
    private const val CB_onError = IBinder.FIRST_CALL_TRANSACTION + 3
    private const val CB_onAmplitude = IBinder.FIRST_CALL_TRANSACTION + 4

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
