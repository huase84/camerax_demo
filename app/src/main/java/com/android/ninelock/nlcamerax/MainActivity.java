package com.android.ninelock.nlcamerax;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.PermissionChecker;
import androidx.core.util.Consumer;

import com.android.ninelock.nlcamerax.databinding.ActivityMainBinding;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding viewBinding;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private ExecutorService cameraExecutor;

    // 摄像头
    private CameraSelector cameraSelector;

    // 切换是拍照还是摄像(true：拍照，false: 录像)
    private boolean isCapture = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());

        // 请求相机权限
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        // 设置拍照按钮的监听器
        viewBinding.imageCaptureButton.setOnClickListener(v -> takePhoto());
        // 设置录像按钮的监听器
        viewBinding.videoCaptureButtonStart.setOnClickListener(v -> captureVideo());
        viewBinding.videoCaptureButtonStop.setOnClickListener(v -> captureVideo());
        // 录像按钮默认隐藏
        viewBinding.videoCaptureButtonStop.setVisibility(View.GONE);
        viewBinding.videoCaptureButtonStart.setVisibility(View.GONE);

        // 切换摄像头
        viewBinding.switchingCameraButton.setOnClickListener(v -> switchingCamera());

        // 切换模式（拍照或录像）
        viewBinding.switchingCameraModePhoto.setOnClickListener(v -> switchingCameraMode(true));
        viewBinding.switchingCameraModeRecording.setOnClickListener(v -> switchingCameraMode(false));

        // 默认显示切换录像模式
        viewBinding.switchingCameraModePhoto.setVisibility(View.GONE);
        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    // 拍照
    private void takePhoto() {
        // 获取可修改的图像捕获用例的稳定引用
        ImageCapture imageCapture = this.imageCapture;
        if (imageCapture == null) {
            return;
        }

        // 创建时间戳名称和 MediaStore 条目
        String name = new SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image");
        }

        // 创建输出选项对象，其中包含文件和元数据
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(),
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
        ).build();

        // 设置图像捕获监听器，在照片拍摄完成后触发
        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onError(@NonNull ImageCaptureException exc) {
                        Log.e(TAG, "照片捕获失败: " + exc.getMessage(), exc);
                    }

                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        String msg = "照片捕获成功: " + output.getSavedUri();
                        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
                        Log.d(TAG, msg);
                    }
                }
        );
    }

    private void captureVideo() {
        // 检查视频捕获实例是否存在
        VideoCapture<Recorder> videoCapture = this.videoCapture;
        if (videoCapture == null) {
            return;
        }

        // 禁用录像按钮
        viewBinding.videoCaptureButtonStart.setEnabled(false);

        // 获取当前录制会话
        Recording curRecording = recording;
        if (curRecording != null) {
            // 停止当前录制会话
            curRecording.stop();
            recording = null;
            return;
        }

        // 创建一个新的录制会话
        String name = new SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video");
        }

        MediaStoreOutputOptions mediaStoreOutputOptions = new MediaStoreOutputOptions.Builder(
                getContentResolver(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build();

        // 准备并开始新的录制会话
        Recorder recorder = videoCapture.getOutput();
        PendingRecording pendingRecording = recorder.prepareRecording(this, mediaStoreOutputOptions);
        // 如果有录音权限，则启用音频录制
        if(ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pendingRecording.withAudioEnabled();
        }

        recording = pendingRecording.start(ContextCompat.getMainExecutor(this), new Consumer<VideoRecordEvent>() {
            @Override
            public void accept(VideoRecordEvent recordEvent) {
                if (recordEvent instanceof VideoRecordEvent.Start) {
                    // 隐藏开始录制按钮，显示停止录制按钮
                    viewBinding.videoCaptureButtonStart.setVisibility(View.GONE);
                    viewBinding.videoCaptureButtonStop.setVisibility(View.VISIBLE);
                    viewBinding.videoCaptureButtonStart.setEnabled(true);
                    // 开始录制后隐藏切换镜头按钮和切换模式按钮
                    viewBinding.switchingCameraButton.setVisibility(View.GONE);

                    viewBinding.switchingCameraModeRecording.setVisibility(View.GONE);
                    viewBinding.switchingCameraModePhoto.setVisibility(View.GONE);

                    Log.d(TAG, "录制开始");
                } else if (recordEvent instanceof VideoRecordEvent.Finalize) {
                    Log.d(TAG, "录制结束");
                    VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) recordEvent;
                    if (!finalizeEvent.hasError()) {
                        // 录制成功时显示消息并记录日志
                        String msg = "视频录制成功: " + finalizeEvent.getOutputResults().getOutputUri();
                        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
                        Log.d(TAG, msg);
                    } else {
                        // 录制失败时关闭录制会话并记录错误日志
                        recording.close();
                        recording = null;
                        Log.e(TAG, "视频录制结束时出错: " + finalizeEvent.getError());
                    }
                    // 录制结束后恢复按钮文本和状态
                    viewBinding.videoCaptureButtonStart.setVisibility(View.VISIBLE);
                    viewBinding.videoCaptureButtonStop.setVisibility(View.GONE);
                    viewBinding.videoCaptureButtonStart.setEnabled(true);
                    // 停止录制后显示切换镜头按钮和切换模式按钮
                    viewBinding.switchingCameraButton.setVisibility(View.VISIBLE);

                    if(isCapture)
                        viewBinding.switchingCameraModeRecording.setVisibility(View.VISIBLE);
                    else {
                        viewBinding.switchingCameraModePhoto.setVisibility(View.VISIBLE);
                    }
                }
            }
        });
    }

    // 启动相机
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                // 将相机生命周期绑定到生命周期所有者
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // 预览
                Preview preview = new Preview.Builder()
                        .build();
                preview.setSurfaceProvider(viewBinding.viewFinder.getSurfaceProvider());

                // 默认拍照模式
                if (isCapture) {
                    // 图像捕获用例
                    imageCapture = new ImageCapture.Builder()
                            .build();

                    ImageAnalysis imageAnalyzer = new ImageAnalysis.Builder().build();
                    Log.d(TAG, "分析器");
                    imageAnalyzer.setAnalyzer(cameraExecutor, new LuminosityAnalyzer(new LuminosityAnalyzer.LumaListener() {
                        @Override
                        public void analyze(double luma) {
                            // 输出当前捕获帧的亮度值
                            Log.d(TAG, "当前帧亮度值: " + luma);
                        }
                    }));
                } else {
                    // 录像模式
                    // 创建 Recorder 实例
                    Recorder recorder = new Recorder.Builder()
                            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                            .build();
                    videoCapture = VideoCapture.withOutput(recorder);
                }

                // 默认选择后置摄像头
                if(cameraSelector == null) {
                    cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                }

                try {
                    // 解绑所有用例后再重新绑定
                    cameraProvider.unbindAll();

                    // 将用例绑定到相机
                    if (isCapture) {
                        // 拍照
                        cameraProvider.bindToLifecycle(
                                this, cameraSelector, preview, imageCapture);
                    } else {
                        // 录像
                        cameraProvider.bindToLifecycle(
                                this, cameraSelector, preview, videoCapture);
                    }


                } catch (Exception exc) {
                    Log.e(TAG, "用例绑定失败", exc);
                }

            } catch (ExecutionException | InterruptedException e) {
                // 处理任何错误（包括取消）
                Log.e(TAG, "CameraProviderFuture 失败", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // 切换摄像头
    private void switchingCamera() {
        if(cameraSelector == null) {
            return;
        }

        // 切换前后摄像头
        if(CameraSelector.DEFAULT_BACK_CAMERA == cameraSelector) {
            cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
        } else {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
        }

        // 重新绑定用例
        startCamera();
    }

    // 切换拍照还是录像
    private void switchingCameraMode(boolean type) {
        isCapture = type;
        Log.d(TAG, "切换为" + (isCapture ? "拍照" : "录像"));
        // 切换拍照/录制按钮显隐
        if(isCapture) {
            viewBinding.videoCaptureButtonStart.setVisibility(View.GONE);
            viewBinding.imageCaptureButton.setVisibility(View.VISIBLE);

            viewBinding.switchingCameraModeRecording.setVisibility(View.VISIBLE);
            viewBinding.switchingCameraModePhoto.setVisibility(View.GONE);
        } else {
            viewBinding.videoCaptureButtonStart.setVisibility(View.VISIBLE);
            viewBinding.imageCaptureButton.setVisibility(View.GONE);

            viewBinding.switchingCameraModeRecording.setVisibility(View.GONE);
            viewBinding.switchingCameraModePhoto.setVisibility(View.VISIBLE);
        }

        startCamera();
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "用户未授予权限。", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private static final String TAG = "CameraXApp";
    private static final String FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static String[] REQUIRED_PERMISSIONS;

    static {
        REQUIRED_PERMISSIONS = new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
        };
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            String[] additionalPermissions = new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
            String[] temp = new String[REQUIRED_PERMISSIONS.length + additionalPermissions.length];
            System.arraycopy(REQUIRED_PERMISSIONS, 0, temp, 0, REQUIRED_PERMISSIONS.length);
            System.arraycopy(additionalPermissions, 0, temp, REQUIRED_PERMISSIONS.length, additionalPermissions.length);
            REQUIRED_PERMISSIONS = temp;
        }
    }
}
