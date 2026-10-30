package dev.ayoub.wristle.data

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.RawMessageStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamAccumulatorTest {
    private val usage =
        """{"input_tokens":1,"output_tokens":1,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,""" +
            """"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":0},"service_tier":"standard"}"""

    private val programmaticWebSearchStream =
        listOf(
            """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5",""" +
                """"content":[],"container":null,"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":$usage}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"code_execution","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"code\":\"run()\"}"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"server_tool_use","id":"srvtoolu_2","name":"web_search",""" +
                """"input":{"query":"weather in Amsterdam"},"caller":{"type":"code_execution_20260120","tool_id":"srvtoolu_1"}}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"Sunny, 18 degrees."}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5}}""",
            """{"type":"message_stop"}""",
        )

    @Test
    fun keepsToolInputThatArrivesOnlyInBlockStart() {
        val accumulator = StreamAccumulator()
        programmaticWebSearchStream.forEach { accumulator.accept(jsonMapper().readValue(it, RawMessageStreamEvent::class.java)) }

        val content = accumulator.message().content()
        assertEquals(3, content.size)
        assertEquals("run()", content[0].asServerToolUse()._input().convert(Map::class.java)!!["code"])
        assertEquals("weather in Amsterdam", content[1].asServerToolUse()._input().convert(Map::class.java)!!["query"])
        assertEquals("Sunny, 18 degrees.", content[2].asText().text())
    }
}
