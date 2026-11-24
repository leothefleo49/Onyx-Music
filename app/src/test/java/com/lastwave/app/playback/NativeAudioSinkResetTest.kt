package com.lastwave.app.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAudioSinkResetTest {

    private fun pcmFormat(
        sampleRate: Int,
        channelCount: Int = 2,
        pcmEncoding: Int = C.ENCODING_PCM_16BIT,
    ): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setSampleRate(sampleRate)
        .setChannelCount(channelCount)
        .setPcmEncoding(pcmEncoding)
        .build()

    private fun createSink(
        enhanced: DefaultAudioSink = mockk(relaxed = true),
        fallback: DefaultAudioSink = mockk(relaxed = true),
        processor: NativePcmAudioProcessor = mockk(relaxed = true),
    ): NativeProcessingAudioSink {
        every { processor.isAvailable } returns false
        every { fallback.getFormatSupport(any()) } returns AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        return NativeProcessingAudioSink(
            enhancedDelegate = enhanced,
            fallbackDelegate = fallback,
            processor = processor,
            outputBufferSizing = OutputBufferSizing(),
        )
    }

    @Test
    fun formatSampleRateOrBitDepthChangeTriggersAtomicSinkResetAndFlush() {
        val enhanced = mockk<DefaultAudioSink>(relaxed = true)
        val fallback = mockk<DefaultAudioSink>(relaxed = true)
        val processor = mockk<NativePcmAudioProcessor>(relaxed = true)
        val sink = createSink(enhanced, fallback, processor)

        // Initial configure establishes baseline format without triggering a switch reset.
        sink.configure(pcmFormat(44_100, 2, C.ENCODING_PCM_16BIT), 0, null)
        assertEquals(0, sink.formatResetCount)

        // Same format reconfiguration does not trigger a destructive format-change reset.
        sink.configure(pcmFormat(44_100, 2, C.ENCODING_PCM_16BIT), 0, null)
        assertEquals(0, sink.formatResetCount)

        // Sample-rate change (44.1 kHz -> 96 kHz) must flush delegate and reset processor.
        sink.configure(pcmFormat(96_000, 2, C.ENCODING_PCM_16BIT), 0, null)
        assertEquals(1, sink.formatResetCount)

        // Bit-depth change (PCM 16-bit -> PCM Float32) must also reset cleanly.
        sink.configure(pcmFormat(96_000, 2, C.ENCODING_PCM_FLOAT), 0, null)
        assertEquals(2, sink.formatResetCount)

        verify(atLeast = 2) { fallback.flush() }
        verify(atLeast = 2) { processor.reset() }
    }

    @Test
    fun sanitizeFloatPcmBufferScrubsNanInfAndDenormalsWhilePreservingValidAudio() {
        val samples = floatArrayOf(
            0.5f,
            Float.NaN,
            -0.25f,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            1e-30f,
            -1e-25f,
            0.99f,
        )
        val buffer = ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder())
        val floats = buffer.asFloatBuffer()
        floats.put(samples)
        buffer.position(0)
        buffer.limit(samples.size * 4)

        NativeProcessingAudioSink.sanitizeFloatPcmBuffer(buffer)

        // Buffer position and limit must remain intact for downstream AudioSink.handleBuffer.
        assertEquals(0, buffer.position())
        assertEquals(samples.size * 4, buffer.limit())

        val out = FloatArray(samples.size)
        buffer.asFloatBuffer().get(out)
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(0.0f, out[1], 0.0f)
        assertEquals(-0.25f, out[2], 1e-6f)
        assertEquals(0.0f, out[3], 0.0f)
        assertEquals(0.0f, out[4], 0.0f)
        assertEquals(0.0f, out[5], 0.0f)
        assertEquals(0.0f, out[6], 0.0f)
        assertEquals(0.99f, out[7], 1e-6f)
    }

    @Test
    fun setVolumeSanitizesNanAndOutOfRangeValuesBeforeForwardingToDelegates() {
        val enhanced = mockk<DefaultAudioSink>(relaxed = true)
        val fallback = mockk<DefaultAudioSink>(relaxed = true)
        val sink = createSink(enhanced, fallback)

        sink.setVolume(0.75f)
        sink.setVolume(Float.NaN)
        sink.setVolume(Float.POSITIVE_INFINITY)
        sink.setVolume(-0.4f)
        sink.setVolume(2.5f)

        verify { enhanced.setVolume(0.75f) }
        verify { enhanced.setVolume(0.0f) }
        verify { enhanced.setVolume(1.0f) }
        verify(exactly = 0) { enhanced.setVolume(match { !it.isFinite() || it < 0f || it > 1f }) }
        verify(exactly = 0) { fallback.setVolume(match { !it.isFinite() || it < 0f || it > 1f }) }
    }

    @Test
    fun audioFocusRoutePolicyAndVolumeSanitizationAreDeterministic() {
        assertEquals(
            AudioRouteAction.PauseAndFlushSink,
            AudioFocusPolicy.routeActionFor(AudioRouteEvent.BecomingNoisy),
        )
        assertEquals(
            AudioRouteAction.FlushAndReconfigureSink,
            AudioFocusPolicy.routeActionFor(AudioRouteEvent.DeviceConnected),
        )
        assertEquals(
            AudioRouteAction.FlushAndReconfigureSink,
            AudioFocusPolicy.routeActionFor(AudioRouteEvent.DeviceDisconnected),
        )
        assertEquals(
            AudioRouteAction.RestoreVolume,
            AudioFocusPolicy.routeActionFor(AudioRouteEvent.FocusRegained),
        )
        assertEquals(0.8f, AudioFocusPolicy.sanitizedVolume(Float.NaN, fallback = 0.8f), 1e-6f)
        assertEquals(1.0f, AudioFocusPolicy.sanitizedVolume(Float.POSITIVE_INFINITY, fallback = Float.NaN), 1e-6f)
        assertTrue(AudioFocusPolicy.duckedVolumeFor(Float.NaN) in 0.1f..0.5f)
    }

    @Test
    fun forcePcm16CompatibilityOutputRoutesSubsequentConfigureThroughFallbackSink() {
        val enhanced = mockk<DefaultAudioSink>(relaxed = true)
        val fallback = mockk<DefaultAudioSink>(relaxed = true)
        val processor = mockk<NativePcmAudioProcessor>(relaxed = true)
        every { processor.isAvailable } returns true
        every { processor.outputSampleRateFor(any()) } returns 48_000
        every { processor.configure(any()) } returns androidx.media3.common.audio.AudioProcessor.AudioFormat(
            48_000,
            2,
            C.ENCODING_PCM_FLOAT,
        )
        every { enhanced.getFormatSupport(any()) } returns AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        every { fallback.getFormatSupport(any()) } returns AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY

        val sink = NativeProcessingAudioSink(
            enhancedDelegate = enhanced,
            fallbackDelegate = fallback,
            processor = processor,
            outputBufferSizing = OutputBufferSizing(),
        )
        sink.forcePcm16CompatibilityOutput("Frozen playhead on Bluetooth route")
        sink.configure(pcmFormat(48_000, 2, C.ENCODING_PCM_16BIT), 0, null)

        verify(exactly = 0) { enhanced.configure(any(), any(), any()) }
        verify(exactly = 1) { fallback.configure(any(), any(), any()) }
    }
}
