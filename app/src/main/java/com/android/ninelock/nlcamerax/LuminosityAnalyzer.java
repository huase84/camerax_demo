package com.android.ninelock.nlcamerax;

import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class LuminosityAnalyzer implements ImageAnalysis.Analyzer {

    private final LumaListener listener;

    public LuminosityAnalyzer(LumaListener listener) {
        this.listener = listener;
    }

    /**
     * 将 ByteBuffer 转换为字节数组
     *
     * @param buffer 要转换的 ByteBuffer
     * @return 字节数组
     */
    private byte[] toByteArray(ByteBuffer buffer) {
        buffer.rewind(); // 将缓冲区重置为零
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data); // 将缓冲区复制到字节数组
        return data; // 返回字节数组
    }

    @Override
    public void analyze(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] data = toByteArray(buffer);

        int[] pixels = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            pixels[i] = data[i] & 0xFF;
        }
        double luma = Arrays.stream(pixels).average().orElse(0.0);

        listener.analyze(luma);

        image.close();
    }

    /**
     * 定义一个接口来处理亮度分析结果
     */
    public interface LumaListener {
        void analyze(double luma);
    }
}
