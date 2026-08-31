package com.hybridinfer.demo

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hybridinfer.HybridRouter
import com.hybridinfer.Message

/**
 * Minimal demo that runs the HybridInfer AAR on-device with stub engines (no
 * model, no network) and logs the routing decisions to logcat under the tag
 * "HybridInferDemo". Proves the library loads, routes, falls back, and self-heals
 * on real Android hardware.
 */
class MainActivity : Activity() {

    private val tag = "HybridInferDemo"
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply {
            text = "HybridInfer AAR on-device demo"
            textSize = 20f
        }
        val btn = Button(this).apply { text = "Run self-test again" }
        out = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 14f
        }
        root.addView(title)
        root.addView(btn)
        root.addView(ScrollView(this).apply { addView(out) })
        setContentView(root)

        btn.setOnClickListener { runSelfTest() }
        runSelfTest()
    }

    private fun runSelfTest() {
        out.text = "running self-test..."
        Thread {
            val report = selfTest()
            runOnUiThread { out.text = report }
        }.start()
    }

    private fun selfTest(): String {
        val sb = StringBuilder()
        fun line(s: String) { sb.append(s).append('\n'); Log.i(tag, s) }

        val local = StubEngine("local", "stub-local", "local-3b", "[local] on-device stub answer")
        val remote = StubEngine("remote", "stub-remote", "remote-70b", "[remote] fallback answer")
        val router = HybridRouter(
            local = local,
            remote = remote,
            riskProfilePath = filesDir.resolve("demo_risk.txt").path,
        )
        val msgs: List<Message> = listOf(mapOf("role" to "user", "content" to "hello from the tablet"))

        line("device=${Build.MODEL} / ${Build.DEVICE}")
        line("--- HybridInfer AAR routing on-device ---")

        local.fail = null
        var r = router.complete(msgs)
        line("1) local OK    -> text=\"${r.text}\" tier=${r.tier} route=${r.route} fellBack=${r.fellBack} state=${router.state()}")

        local.fail = "stall"
        r = router.complete(msgs)
        line("2) local STALL -> text=\"${r.text}\" tier=${r.tier} route=${r.route} fellBack=${r.fellBack} state=${router.state()}")

        r = router.complete(msgs)
        line("3) local STALL -> text=\"${r.text}\" tier=${r.tier} route=${r.route} fellBack=${r.fellBack} state=${router.state()}")

        r = router.complete(msgs)
        line("4) local STALL -> text=\"${r.text}\" tier=${r.tier} route=${r.route} fellBack=${r.fellBack} state=${router.state()}")

        line("risk(local)=${router.riskSnapshot()}")
        line("expected: 1=local; 2,3=remote(fellBack=true); 4=remote(local held out, state=UNSAFE)")
        line("DONE")
        return sb.toString()
    }
}
