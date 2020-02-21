package net.ossrs.yasea.demo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;

import net.ossrs.yasea.SrsCameraView;
import net.ossrs.yasea.SrsEncoder;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 相机管理类
 * @author xiaosh
 */
public class Camera2Manager {

    private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

    static
    {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    static {
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
    }

    private SrsCameraView previewView;

    // 摄像头ID（通常0代表后置摄像头，1代表前置摄像头）
    private String mCameraId = "1";
    // 定义代表摄像头的成员变量
    private CameraDevice cameraDevice;
    // 预览尺寸
    private Size previewSize;
    private CaptureRequest.Builder previewRequestBuilder;
    // 定义用于预览照片的捕获请求
    private CaptureRequest previewRequest;
    // 定义CameraCaptureSession成员变量
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Surface mRecorderSurface;

    private HandlerThread mWorkThread;
    private Handler mWorkHandler;

    private MediaRecorder mMediaRecorder;

    private String mNextVideoAbsolutePath;

    private Integer mSensorOrientation;

    private Range<Integer>  fpsRang;

    private volatile boolean mIsRecordingVideo;

    /** 摄像头、视频录制等状态回调 */
    private OnStateCallback onStateCallback;

    private Context mContext;

    public Camera2Manager(Context context) {
        this.mContext = context;
        startWorkThread();
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice. StateCallback() {
        // 摄像头被打开时激发该方法
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            Camera2Manager.this.cameraDevice = cameraDevice;
            // 开始预览
            createCameraPreviewSession();  // ②
            if (onStateCallback != null) {
                onStateCallback.onCameraOpened();
            }
        }

        // 摄像头断开连接时激发该方法
        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            cameraDevice.close();
            Camera2Manager.this.cameraDevice = null;
        }

        // 打开摄像头出现错误时激发该方法
        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            cameraDevice.close();
            Camera2Manager.this.cameraDevice = null;
        }
    };

    public void setPreviewView(SrsCameraView previewView) {
        this.previewView = previewView;
    }

    private void startWorkThread() {
        mWorkThread = new HandlerThread("CameraThread");
        mWorkThread.start();
        mWorkHandler = new Handler(mWorkThread.getLooper());
    }

    private void stopWorkThread() {
        try {
            mWorkThread.quitSafely();
            mWorkThread.join();
            mWorkThread = null;
        } catch (InterruptedException | NullPointerException e) {
            e.printStackTrace();
        } finally {
            if (mWorkHandler != null) {
                mWorkHandler.removeCallbacksAndMessages(null);
            }
            mWorkHandler = null;
        }
    }

    /**
     * 打开摄像头
     *
     * @param width 预览宽
     * @param height 预览高
     */
    @SuppressLint("MissingPermission")
    public void openCamera(int width, int height) {
        if (previewView == null) {
            return;
        }
        CameraManager mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        setUpCameraOutputs(mCameraManager, width, height);
        try {
            //  ① 打开摄像头
            mCameraManager.openCamera(mCameraId, stateCallback, null);
        }
        catch (CameraAccessException e) {
            Log.e("openCamera", "CameraAccessException: " + e.getMessage());
        }
    }

    public void closeCamera() {
        closePreviewSession();
        if (mMediaRecorder != null) {
            mMediaRecorder.stop();
            mMediaRecorder.release();
            mMediaRecorder = null;
        }

        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        stopWorkThread();
    }

    private void createCameraPreviewSession() {
        closePreviewSession();
        try {
            SurfaceTexture surfaceTexture = previewView.getSurfaceTexture();
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            // 创建作为预览的CaptureRequest.Builder
            previewRequestBuilder = cameraDevice
                    .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            // 将textureView的surface作为CaptureRequest.Builder的目标
            Surface surface = new Surface(surfaceTexture);
            previewRequestBuilder.addTarget(surface);
            // 创建CameraCaptureSession，该对象负责管理处理预览请求和拍照请求
            cameraDevice.createCaptureSession(Arrays.asList(surface, imageReader.getSurface())
                    , new CameraCaptureSession.StateCallback() // ③
                    {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // 如果摄像头为null，直接结束方法
                            if (null == cameraDevice) {
                                return;
                            }
                            // 当摄像头已经准备好时，开始显示预览
                            captureSession = cameraCaptureSession;
                            try {
                                // 设置自动对焦模式
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
//                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, );
                                // 设置自动曝光模式
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH);
                                // 设置自动白平衡
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
                                // 设置情景模式
//                                previewRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_NIGHT);
                                // 设置 FPS
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRang);
                                // 开始显示相机预览
                                previewRequest = previewRequestBuilder.build();
                                // 设置预览时连续捕获图像数据
                                captureSession.setRepeatingRequest(previewRequest,
                                        null, mWorkHandler);  // ④
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                        }
                    }, mWorkHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * 录制、拍照后更新预览，否则界面画面停住
     */
    private void updatePreview() {
        if (null == cameraDevice) {
            return;
        }
        try {
            setUpCaptureRequestBuilder(previewRequestBuilder);
            captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, mWorkHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closePreviewSession() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
    }

    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
    }

    private void setUpCameraOutputs(CameraManager mCameraManager, int width, int height) {
        try {
            // 获取指定摄像头的特性
            CameraCharacteristics characteristics
                    = mCameraManager.getCameraCharacteristics(mCameraId);
            // 获取摄像头支持的配置属性
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Range<Integer> []fpsRange = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            this.fpsRang = adaptFpsRange(SrsEncoder.VFPS, fpsRange);
            // 获取摄像头支持的最大尺寸
            Size largest = Collections.max(
                    Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                    new CompareSizesByArea());
            // 创建一个ImageReader对象，用于获取摄像头的图像数据
            imageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                    ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(
                    new ImageReader.OnImageAvailableListener()
                    {
                        // 当照片数据可用时激发该方法
                        @Override
                        public void onImageAvailable(ImageReader reader)
                        {
                            // 获取捕获的照片数据
                            Image image = reader.acquireLatestImage();
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            if (onStateCallback != null) {
                                onStateCallback.onCapture(bytes);
                            }
                            image.close();
                        }
                    }, mWorkHandler);

            mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            // 获取最佳的预览尺寸
            previewSize = chooseOptimalSize(map.getOutputSizes(
                    SurfaceTexture.class), width, height, largest);
            Log.e("===", "previewSize: " + previewSize);
            // 根据选中的预览尺寸来调整预览组件（TextureView）的长宽比
            int orientation = previewView.getContext().getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                previewView.setPreviewResolution(previewSize.getWidth(), previewSize.getHeight());
            } else {
                previewView.setPreviewResolution(previewSize.getHeight(), previewSize.getWidth());
            }
        } catch (CameraAccessException e) {
            Log.e("setUpCameraOutputs", "CameraAccessException : " + e.getMessage());
        } catch (NullPointerException e) {
            Log.e("setUpCameraOutputs", "NullPointerException : " + e.getMessage());
        }
    }

    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // 收集摄像头支持的大过预览Surface的分辨率
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        // 如果找到多个预览尺寸，获取其中面积最小的
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e("chooseOptimalSize", "找不到合适的预览尺寸！！！");
            return choices[0];
        }
    }

    /**
     * 找出包含指定帧率的，范围最广的一组
     *
     * @param expectedFps 预定帧率
     * @param fpsRanges 支持的帧率范围
     */
    private Range<Integer> adaptFpsRange(int expectedFps, Range<Integer> []fpsRanges) {
        Range<Integer> closestRange = fpsRanges[0];
        int measure = Math.abs(closestRange.getLower() - closestRange.getUpper());
        for (Range<Integer> range : fpsRanges) {
            if (range.getLower() <= expectedFps && range.getUpper() >= expectedFps) {
                int curMeasure = Math.abs(range.getLower() - range.getUpper());
                if (curMeasure > measure) {
                    closestRange = range;
                    measure = curMeasure;
                }
            }
        }
        return closestRange;
    }

    // 为Size定义一个比较器Comparator
    private static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // 强转为long保证不会发生溢出
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    /**
     * 捕获静态照片
     */
    public void captureStillPicture() {
        try {
            if (cameraDevice == null) {
                return;
            }
            // 创建作为拍照的CaptureRequest.Builder
            final CaptureRequest.Builder captureRequestBuilder = cameraDevice
                    .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            // 将imageReader的surface作为CaptureRequest.Builder的目标
            captureRequestBuilder.addTarget(imageReader.getSurface());
            // 设置自动对焦模式
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            // 设置自动曝光模式
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            // 根据设备方向计算设置照片的方向
            int rotation = ((Activity) previewView.getContext()).getWindowManager().getDefaultDisplay().getRotation();
            switch (mSensorOrientation) {
                case SENSOR_ORIENTATION_DEFAULT_DEGREES:
                    captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
                    break;
                case SENSOR_ORIENTATION_INVERSE_DEGREES:
                    captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, INVERSE_ORIENTATIONS.get(rotation));
                    break;
            }
            // 停止连续取景
            captureSession.stopRepeating();
            // 捕获静态图像
            captureSession.capture(captureRequestBuilder.build()
                    , new CameraCaptureSession.CaptureCallback()  // ⑤
                    {
                        // 拍照完成时激发该方法
                        @Override
                        public void onCaptureCompleted(CameraCaptureSession session
                                , CaptureRequest request, TotalCaptureResult result) {
                            try {
                                // 重设自动对焦模式
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                        CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                                // 设置自动曝光模式
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                                // 打开连续取景模式
                                captureSession.setRepeatingRequest(previewRequest, null, null);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }
                    }, mWorkHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void setOnStateCallback(OnStateCallback stateCallback) {
        this.onStateCallback = stateCallback;
    }

    /**
     * 视频录制状态回调
     */
    public interface OnStateCallback {
        /**
         * 视频录制开始
         */
        void onRecordingStart();

        /**
         * 视频录制结束
         */
        void onRecordingStop();

        /**
         * 摄像头打开成功回调
         */
        void onCameraOpened();

        /**
         * 拍照成功回调
         *
         * @param data 图片直接数据
         */
        void onCapture(byte[] data);
    }
}
