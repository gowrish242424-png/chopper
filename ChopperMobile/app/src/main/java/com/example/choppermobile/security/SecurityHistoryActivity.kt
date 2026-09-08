package com.example.choppermobile.security

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.choppermobile.data.ChopperDatabase
import java.text.DateFormat
import java.util.Date

class SecurityHistoryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Security history"
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(list) })
        Thread {
            val events = ChopperDatabase.getInstance(applicationContext).chopperDao().getSecurityEvents()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (events.isEmpty()) {
                    list.addView(TextView(this).apply { text = "No security events yet."; textSize = 18f })
                } else {
                    events.forEach { event ->
                        list.addView(TextView(this).apply {
                            text = "${DateFormat.getDateTimeInstance().format(Date(event.timestamp))}\n${event.eventType} · ${event.verificationResult}\nQuality: ${event.qualityResult}\n${event.details}"
                            textSize = 16f
                            setPadding(0, 0, 0, 28)
                        })
                    }
                }
            }
        }.start()
    }
}
