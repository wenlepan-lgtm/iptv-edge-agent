package com.example.androidtvspeechrecognition

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV文件工具类
 */
object WavUtils {
    
    /**
     * 创建标准WAV文件头
     * @param audioDataSize 音频数据大小(字节)
     * @param sampleRate 采样率
     * @param channels 声道数
     * @param bitsPerSample 位深度
     * @return WAV文件头字节数组
     */
    fun createWavHeader(
        audioDataSize: Int,
        sampleRate: Int = 16000,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val header = ByteBuffer.allocate(44)
        header.order(ByteOrder.LITTLE_ENDIAN)
        
        // RIFF header
        header.put("RIFF".toByteArray())           // Chunk ID (4 bytes)
        header.putInt(36 + audioDataSize)          // Chunk Size (4 bytes)
        header.put("WAVE".toByteArray())           // Format (4 bytes)
        
        // fmt subchunk
        header.put("fmt ".toByteArray())           // Subchunk1 ID (4 bytes)
        header.putInt(16)                          // Subchunk1 Size (4 bytes)
        header.putShort(1.toShort())               // Audio Format (2 bytes) - PCM
        header.putShort(channels.toShort())        // Number of Channels (2 bytes)
        header.putInt(sampleRate)                   // Sample Rate (4 bytes)
        header.putInt(sampleRate * channels * bitsPerSample / 8)  // Byte Rate (4 bytes)
        header.putShort((channels * bitsPerSample / 8).toShort()) // Block Align (2 bytes)
        header.putShort(bitsPerSample.toShort())   // Bits Per Sample (2 bytes)
        
        // data subchunk
        header.put("data".toByteArray())           // Subchunk2 ID (4 bytes)
        header.putInt(audioDataSize)               // Subchunk2 Size (4 bytes)
        
        return header.array()
    }
    
    /**
     * 将Short数组保存为WAV文件
     * @param samples 音频样本数据
     * @param filePath 文件路径
     * @param sampleRate 采样率
     * @param channels 声道数
     * @param bitsPerSample 位深度
     */
    fun saveAsWavFile(
        samples: ShortArray,
        filePath: String,
        sampleRate: Int = 16000,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ) {
        val file = File(filePath)
        val fos = FileOutputStream(file)
        
        try {
            // 计算音频数据大小
            val audioDataSize = samples.size * (bitsPerSample / 8)
            
            // 写入WAV文件头
            val header = createWavHeader(audioDataSize, sampleRate, channels, bitsPerSample)
            fos.write(header)
            
            // 写入音频数据
            val buffer = ByteBuffer.allocate(samples.size * 2)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                buffer.putShort(sample)
            }
            fos.write(buffer.array())
        } finally {
            fos.close()
        }
    }
}