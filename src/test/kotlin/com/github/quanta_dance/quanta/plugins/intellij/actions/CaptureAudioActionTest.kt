package com.github.quanta_dance.quanta.plugins.intellij.actions

import kotlin.test.Ignore

class CaptureAudioActionTest {

  //  var openAI = OpenAIKotlinClient("https://api.openai.com/v1/", System.getenv("OPENAI_API_KEY"))

    //@Test
    @Ignore
    fun genLogo() {

        val prom =
            "create me logo for the IDEA plugin QuantaDance which will extend developer brain with AI Power, this logo must be in simple single color to match other plugins buttons. It should represent mind, plug and AI. it should be icon for the button. it must be single color and simple lines. It MUST be white color on gray"

    //    val res = openAI.image().generate(prom)
    //    println(res)

    }

    //  @Test
    @Ignore
    fun test() {

        //  val rec = Recorder()
        // val audio = rec.recordAudio()
//        val transcript = client.audio().transcription(audio)
        //  println(transcript)

        val transcript = "Is there anything can be improved in the code?"

//        val answer = openAI.chat().complete(
//            messages = listOf(
//                Message(
//                    role = "system", arrayOf(
//                        TextContent(
//                            text = "You are lead engineer and help user to answer questions regarding information provided by assistant. Remember, user request was transribed from voice message." + "You must respond with short audio message"
//                            //         "You should strictly answer in scope of the file changes and correct user if question is unrelated"
//                        )
//                    )
//                ), Message(
//                    role = "assistant", arrayOf(
//                        TextContent(
//                            text = """
//                    file: OpenAIClient.kt
//
// package com.github.quanta_dance.quanta.plugins.intellij.openai.client
//
//
//import io.ktor.client.HttpClient
//import io.ktor.client.engine.cio.*
//import io.ktor.client.plugins.*
//import io.ktor.client.plugins.logging.*
//import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
//import io.ktor.client.request.*
//import io.ktor.http.*
//import io.ktor.serialization.gson.gson
//
///**
// *
// */
//class OpenAIClient(val baseUrl: String, val token: String) {
//
//
//    // migrate to https://github.com/openai/openai-java
//    val http = HttpClient(CIO) {
//        install(HttpTimeout) {
//            connectTimeoutMillis = 1_000
//            requestTimeoutMillis = 60_000
//        }
//
//        install(DefaultRequest) {
//            url(baseUrl)
//            header("Authorization", "Bearer token")
//        }
//        install(ContentNegotiation) {
//            gson() // Use Gson for JSON serialization/deserialization
//        }
//        install(Logging) {
//            level = LogLevel.ALL
//            logger = Logger.DEFAULT
//        }
//    }
//
//    /**
//     * Return Audio API
//     */
//    fun audio() = Audio(http)
//
//    fun chat() = Chat(http)
//
//    fun image() = Image(http)
//}
//
//                """.trimIndent()
//                        )
//                    )
//                ), Message(
//                    content = arrayOf(
////                            AudioContent(
////                                input_audio = AudioContentInput(
////                                    data = Base64.getEncoder().encodeToString(audio)
////                                )
////                            ),
//                        TextContent(
//                            text = transcript
//                        )
//                    )
//                )
//            )
//        )
//
//
//
////        val res = client.audio().speech(
////            input = answer,
////            responseFormat = "mp3"
////        )
//
////        val res = answer.coreReviewResponse?.audio_transcript
////
////        if (res != null) {
////            com.github.quanta_dance.quanta.plugins.intellij.sound.Player.playMp3(res)
////        } else {
////            println("No audio response")
////        }
//
    }

}
