package net.ossrs.yasea;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.AttributeSet;

import com.seu.magicfilter.base.gpuimage.GPUImageFilter;
import com.seu.magicfilter.utils.MagicFilterFactory;
import com.seu.magicfilter.utils.MagicFilterType;
import com.seu.magicfilter.utils.OpenGLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Created by Leo Ma on 2016/2/25.
 */
public class SrsCameraView extends GLSurfaceView implements GLSurfaceView.Renderer {

    private GPUImageFilter magicFilter;
    private SurfaceTexture surfaceTexture;
    private int mOESTextureId = OpenGLUtils.NO_TEXTURE;
    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private int mPreviewWidth;
    private int mPreviewHeight;
    private volatile boolean mIsEncoding;
    private volatile boolean mRequestCaptureFrame;
    private float mInputAspectRatio;
    private float mOutputAspectRatio;
    private float[] mProjectionMatrix = new float[16];
    private float[] mSurfaceMatrix = new float[16];
    private float[] mTransformMatrix = new float[16];

    private ByteBuffer mGLPreviewBuffer;

    private Thread worker;
    private final Object writeLock = new Object();
    private ConcurrentLinkedQueue<IntBuffer> mGLIntBufferCache = new ConcurrentLinkedQueue<>();
    private PreviewCallback mPrevCb;
    private SurfaceCreatedCallback surfaceCreatedCallback;
    private CaptureFrameCallback captureFrameCallback;

    // 预览屏幕方向
    private int previewOrientation;

    public SrsCameraView(Context context) {
        this(context, null);
    }

    public SrsCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.previewOrientation = getContext().getResources().getConfiguration().orientation;
        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glDisable(GL10.GL_DITHER);
        GLES20.glClearColor(0, 0, 0, 0);
        magicFilter = new GPUImageFilter();
        magicFilter.init(getContext());

        mOESTextureId = OpenGLUtils.getExternalOESTextureID();
        surfaceTexture = new SurfaceTexture(mOESTextureId);
        surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                requestRender();
            }
        });

        if (surfaceCreatedCallback != null) {
            surfaceCreatedCallback.onSurfaceCreated();
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        mSurfaceWidth = width;
        mSurfaceHeight = height;
        magicFilter.onDisplaySizeChanged(width, height);
        magicFilter.onInputSizeChanged(mPreviewWidth, mPreviewHeight);

        mOutputAspectRatio = width > height ? (float) width / height : (float) height / width;
        float aspectRatio = mOutputAspectRatio / mInputAspectRatio;
        if (width > height) {
            Matrix.orthoM(mProjectionMatrix, 0, -1.0f, 1.0f, -aspectRatio, aspectRatio, -1.0f, 1.0f);
        } else {
            Matrix.orthoM(mProjectionMatrix, 0, -aspectRatio, aspectRatio, -1.0f, 1.0f, -1.0f, 1.0f);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        surfaceTexture.updateTexImage();
        surfaceTexture.getTransformMatrix(mSurfaceMatrix);

        Matrix.multiplyMM(mTransformMatrix, 0, mSurfaceMatrix, 0, mProjectionMatrix, 0);

        magicFilter.setTextureTransformMatrix(mTransformMatrix);
        magicFilter.onDrawFrame(mOESTextureId);

        if (mIsEncoding) {
            mGLIntBufferCache.add(magicFilter.getGLFboBuffer());
            synchronized (writeLock) {
                writeLock.notifyAll();
            }
        }

        // 拍照
        if (mRequestCaptureFrame && captureFrameCallback != null) {
            IntBuffer picture = magicFilter.getGLFboBuffer();
            ByteBuffer buf = ByteBuffer.allocateDirect(mPreviewWidth * mPreviewHeight * 4);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.asIntBuffer().put(picture.array());
            buf.rewind();
            captureFrameCallback.onCaptureFrame(buf.array(), mPreviewWidth, mPreviewHeight);
            buf.clear();
            mRequestCaptureFrame = false;
        }
    }

    /**
     * 请求截取帧
     */
    public void requestCaptureFrame() {
        this.mRequestCaptureFrame =  true;
    }

    public SurfaceTexture getSurfaceTexture() {
        return surfaceTexture;
    }

    public void setPreviewCallback(PreviewCallback cb) {
        mPrevCb = cb;
    }

    public void setCaptureFrameCallback(CaptureFrameCallback captureFrameCallback) {
        this.captureFrameCallback = captureFrameCallback;
    }

    public int[] setPreviewResolution(int width, int height) {
        if (width == 0 || height == 0) {
            return new int[] { mPreviewWidth, mPreviewHeight };
        }
        mPreviewWidth = width;
        mPreviewHeight = height;

        //设定宽高比，调整预览窗口大小（调整后窗口大小不超过默认值）
        if (mSurfaceWidth < mSurfaceHeight * mPreviewWidth / mPreviewHeight){
            mSurfaceHeight =  mSurfaceWidth * mPreviewHeight / mPreviewWidth;
        }else {
            mSurfaceWidth = mSurfaceHeight * mPreviewWidth / mPreviewHeight;
        }

        getHolder().setFixedSize(mSurfaceWidth, mSurfaceHeight);

        mGLPreviewBuffer = ByteBuffer.allocate(mPreviewWidth * mPreviewHeight * 4);
        mInputAspectRatio = mPreviewWidth > mPreviewHeight ?
            (float) mPreviewWidth / mPreviewHeight : (float) mPreviewHeight / mPreviewWidth;

        return new int[] { mPreviewWidth, mPreviewHeight };
    }

    /**
     * 设置预览的屏幕方向：Configuration.ORIENTATION_PORTRAIT or Configuration.ORIENTATION_LANDSCAPE
     *
     * @param orientation
     */
    public void setPreviewOrientation(int orientation) {
        if (previewOrientation == orientation) {
            return;
        }
        this.previewOrientation = orientation;
        // 如果方向改变了，交换宽高
        int w = mPreviewHeight;
        int h = mPreviewWidth;
        mPreviewWidth = w;
        mPreviewHeight = h;
        setPreviewResolution(mPreviewWidth, mPreviewHeight);
        updateResolution();
    }

    private void updateResolution() {
        queueEvent(new Runnable() {
            @Override
            public void run() {
                if (magicFilter != null) {
                    magicFilter.destroy();
                    magicFilter.init(getContext());
                    magicFilter.onInputSizeChanged(mPreviewWidth, mPreviewHeight);
                    magicFilter.onDisplaySizeChanged(mSurfaceWidth, mSurfaceHeight);
                }
            }
        });
    }

    public boolean setFilter(final MagicFilterType type) {
        queueEvent(new Runnable() {
            @Override
            public void run() {
                if (magicFilter != null) {
                    magicFilter.destroy();
                }
                magicFilter = MagicFilterFactory.initFilters(type);
                if (magicFilter != null) {
                    magicFilter.init(getContext());
                    magicFilter.onInputSizeChanged(mPreviewWidth, mPreviewHeight);
                    magicFilter.onDisplaySizeChanged(mSurfaceWidth, mSurfaceHeight);
                }
            }
        });
        requestRender();
        return true;
    }

    private void deleteTextures() {
        if (mOESTextureId != OpenGLUtils.NO_TEXTURE) {
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    GLES20.glDeleteTextures(1, new int[]{ mOESTextureId }, 0);
                    mOESTextureId = OpenGLUtils.NO_TEXTURE;
                }
            });
        }
    }

    public void enableEncoding() {
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.interrupted()) {
                    while (!mGLIntBufferCache.isEmpty()) {
                        IntBuffer picture = mGLIntBufferCache.poll();
                        mGLPreviewBuffer.asIntBuffer().put(picture.array());
                        mPrevCb.onGetRgbaFrame(mGLPreviewBuffer.array(), mPreviewWidth, mPreviewHeight);
                    }
                    // Waiting for next frame
                    synchronized (writeLock) {
                        try {
                            // isEmpty() may take some time, so we set timeout to detect next frame
                            writeLock.wait(500);
                        } catch (InterruptedException ie) {
                            worker.interrupt();
                        }
                    }
                }
            }
        });
        worker.start();
        mIsEncoding = true;
    }

    public void disableEncoding() {
        mIsEncoding = false;
        mGLIntBufferCache.clear();
        mGLPreviewBuffer.clear();

        if (worker != null) {
            worker.interrupt();
            try {
                worker.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
                worker.interrupt();
            }
            worker = null;
        }
    }

    public interface PreviewCallback {

        void onGetRgbaFrame(byte[] data, int width, int height);
    }

    public interface CaptureFrameCallback {
        void onCaptureFrame(byte[] data, int width, int height);
    }

    /**
     * SurfaceTexture 创建成功回调
     */
    public interface SurfaceCreatedCallback {
        /**
         * SurfaceTexture 创建成功回调，此方法在子线程中回调
         */
        void onSurfaceCreated();
    }

    public void setSurfaceCreatedCallback(SurfaceCreatedCallback surfaceCreatedCallback) {
        this.surfaceCreatedCallback = surfaceCreatedCallback;
    }
}
