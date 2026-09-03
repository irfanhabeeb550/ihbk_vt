package com.habeeb.transcriberecorder.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class GroqSegment(val start: Double, val end: Double, val text: String)

@Serializable
data class GroqTranscriptionResponse(
    val text: String,
    val segments: List<GroqSegment> = emptyList()
)

object GroqApi {

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    /** Transcribes one audio chunk. vocabularyHints is a short comma-separated string of
     *  proper nouns / jargon the user expects (professor name, topic terms), passed as
     *  Whisper's "prompt" to bias recognition. */
    suspend fun transcribeChunk(
        apiKey: String,
        chunk: File,
        vocabularyHints: String = ""
    ): GroqTranscriptionResponse {
        val response: GroqTranscriptionResponse = client.submitFormWithBinaryData(
            url = "https://api.groq.com/openai/v1/audio/transcriptions",
            formData = formData {
                append("file", chunk.readBytes(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=${chunk.name}")
                })
                append("model", "whisper-large-v3")
                append("response_format", "verbose_json")
                if (vocabularyHints.isNotBlank()) append("prompt", vocabularyHints)
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }.body()
        return response
    }

    @Serializable
    private data class ChatMessage(val role: String, val content: String)
    @Serializable
    private data class ChatRequest(val model: String, val messages: List<ChatMessage>)
    @Serializable
    private data class ChatChoice(val message: ChatMessage)
    @Serializable
    private data class ChatResponse(val choices: List<ChatChoice>)

    /** Sends the stitched transcript to Groq's Llama model for a class/meeting-style summary. */
    suspend fun summarize(apiKey: String, transcript: String, category: String): String {
        val prompt = "The following is a transcript of a $category recording. " +
            "Give a concise summary, a list of key terms defined, and 5-7 bulleted takeaways. " +
            "Format the reply in Markdown.\n\nTranscript:\n$transcript"

        val requestBody = ChatRequest(
            model = "llama-3.3-70b-versatile",
            messages = listOf(ChatMessage(role = "user", content = prompt))
        )

        val response: ChatResponse = client.post("https://api.groq.com/openai/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }.body()

        return response.choices.firstOrNull()?.message?.content ?: ""
    }
}
