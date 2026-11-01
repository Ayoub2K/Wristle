package dev.ayoub.wristle.chat

import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.InputJsonDelta
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.RawContentBlockDeltaEvent
import com.anthropic.models.messages.RawMessageStreamEvent
import kotlin.jvm.optionals.getOrNull

class StreamAccumulator {
    private val accumulator = MessageAccumulator.create()
    private val inputWithoutDeltas = mutableMapOf<Long, JsonValue>()

    fun accept(event: RawMessageStreamEvent) {
        event.contentBlockStart().getOrNull()?.let { start ->
            val block = start.contentBlock()
            val input = block.toolUse().getOrNull()?._input() ?: block.serverToolUse().getOrNull()?._input()
            if (input != null) inputWithoutDeltas[start.index()] = input
        }
        event.contentBlockDelta().getOrNull()?.let { delta ->
            if (delta.delta().isInputJson()) inputWithoutDeltas.remove(delta.index())
        }
        event.contentBlockStop().getOrNull()?.let { stop ->
            inputWithoutDeltas.remove(stop.index())?.let { accumulator.accumulate(inputDelta(stop.index(), it)) }
        }
        accumulator.accumulate(event)
    }

    fun message(): Message = accumulator.message()

    private fun inputDelta(
        index: Long,
        input: JsonValue,
    ): RawMessageStreamEvent =
        RawMessageStreamEvent.ofContentBlockDelta(
            RawContentBlockDeltaEvent
                .builder()
                .index(index)
                .delta(InputJsonDelta.builder().partialJson(jsonMapper().writeValueAsString(input)).build())
                .build(),
        )
}
