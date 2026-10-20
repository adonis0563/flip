package com.example.service.control

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Control channel messages exchanged between Flip devices over port 8081.
 * Formatted as newline-delimited JSON, max 512 bytes each.
 */
sealed class ControlMessage {
    data class Hello(
        val app: String = "flip",
        val v: Int = 1,
        val uuid: String,
        val name: String
    ) : ControlMessage()

    data class HelloAck(
        val uuid: String,
        val name: String
    ) : ControlMessage()

    object Ready : ControlMessage()

    object ReadyAck : ControlMessage()

    object Ping : ControlMessage()

    object Pong : ControlMessage()

    data class Error(
        val error: String
    ) : ControlMessage()

    fun toJsonString(): String {
        val json = JSONObject()
        when (this) {
            is Hello -> {
                json.put("app", app)
                json.put("v", v)
                json.put("uuid", ControlChannelConstants.normalizeUuid(uuid))
                json.put("name", name)
                json.put("type", "HELLO")
            }
            is HelloAck -> {
                json.put("uuid", ControlChannelConstants.normalizeUuid(uuid))
                json.put("name", name)
                json.put("type", "HELLO_ACK")
            }
            is Ready -> {
                json.put("type", "READY")
            }
            is ReadyAck -> {
                json.put("type", "READY_ACK")
            }
            is Ping -> {
                json.put("type", "PING")
            }
            is Pong -> {
                json.put("type", "PONG")
            }
            is Error -> {
                json.put("error", error)
            }
        }
        return json.toString()
    }

    companion object {
        /**
         * Parses a JSON string into a ControlMessage.
         * Returns null if string is malformed or unrecognized.
         */
        fun parse(raw: String): ControlMessage? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            return try {
                val json = JSONObject(trimmed)

                if (json.has("error")) {
                    return Error(error = json.getString("error"))
                }

                val type = json.optString("type", "")
                when {
                    type.equals("HELLO", ignoreCase = true) || (json.optString("app") == "flip" && json.has("uuid")) -> {
                        val uuid = json.optString("uuid")
                        val name = json.optString("name", "Unknown")
                        if (uuid.isNullOrBlank()) null else Hello(
                            app = json.optString("app", "flip"),
                            v = json.optInt("v", 1),
                            uuid = ControlChannelConstants.normalizeUuid(uuid),
                            name = name
                        )
                    }
                    type.equals("HELLO_ACK", ignoreCase = true) || (json.has("uuid") && !json.has("app")) -> {
                        val uuid = json.optString("uuid")
                        val name = json.optString("name", "Unknown")
                        if (uuid.isNullOrBlank()) null else HelloAck(
                            uuid = ControlChannelConstants.normalizeUuid(uuid),
                            name = name
                        )
                    }
                    type.equals("READY", ignoreCase = true) -> Ready
                    type.equals("READY_ACK", ignoreCase = true) -> ReadyAck
                    type.equals("PING", ignoreCase = true) -> Ping
                    type.equals("PONG", ignoreCase = true) -> Pong
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Reads a newline-delimited line from the given InputStream up to maxBytes.
         * If the line exceeds maxBytes before a newline, it consumes remaining bytes until \n
         * and returns null to safely ignore oversized messages.
         */
        fun readLineBounded(input: InputStream, maxBytes: Int = ControlChannelConstants.MAX_MESSAGE_BYTES): String? {
            val baos = ByteArrayOutputStream()
            var b: Int
            var isOversized = false

            while (true) {
                b = input.read()
                if (b == -1) {
                    if (baos.size() == 0 || isOversized) return null
                    break
                }
                if (b == '\n'.code) {
                    break
                }
                if (b == '\r'.code) {
                    continue
                }

                if (!isOversized) {
                    if (baos.size() < maxBytes) {
                        baos.write(b)
                    } else {
                        isOversized = true
                    }
                }
            }

            if (isOversized) {
                return null
            }
            return baos.toString(StandardCharsets.UTF_8.name())
        }

        /**
         * Reads and parses a single ControlMessage from input stream.
         * Ignores frames that are oversized or malformed.
         */
        fun readMessage(input: InputStream, maxBytes: Int = ControlChannelConstants.MAX_MESSAGE_BYTES): ControlMessage? {
            val line = readLineBounded(input, maxBytes) ?: return null
            return parse(line)
        }

        /**
         * Writes a ControlMessage followed by a newline (\n) to the output stream.
         */
        fun writeMessage(output: OutputStream, message: ControlMessage) {
            val payload = message.toJsonString() + "\n"
            output.write(payload.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }
    }
}
