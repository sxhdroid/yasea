package com.seu.magicfilter.base.gpuimage;

import android.app.Activity;
import android.content.Context;
import android.opengl.GLES20;
import android.view.Surface;

import com.seu.magicfilter.utils.MagicFilterType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 自动旋转预览视图
 *
 * @author xiaosh
 * @date 2020-02-21 14:27
 */
public class GPUImageRotationFilter extends GPUImageFilter {

    public GPUImageRotationFilter(){
        this(MagicFilterType.ROTATION);
    }

    public GPUImageRotationFilter(MagicFilterType type) {
        super(type);
    }

    /**
     * 顶点
     */
    private final float VEX_CUBE[] = {
            // Bottom left.
            -1.0f, -1.0f,
            // Bottom right.
            1.0f, -1.0f,
            // Top left.
            -1.0f, 1.0f,
            // Top right.
            1.0f, 1.0f,
    };
    /**
     * 逆时针旋转0°
     */
    private final float TEX_COORD_ROTATION_0[] = {
            // Bottom left.
            0.0f, 0.0f,
            // Bottom right.
            1.0f, 0.0f,
            // Top left.
            0.0f, 1.0f,
            // Top right.
            1.0f, 1.0f
    };

    /**
     * 在0°基础上逆时针旋转90°
     */
    private final float TEX_COORD_ROTATION_90[] = {
            // Bottom left.
            0.0f, 1.0f,
            // Bottom right.
            0.0f, 0.0f,
            // Top left.
            1.0f, 1.0f,
            // Top right.
            1.0f, 0.0f
    };

    /**
     * 在0°基础上顺时针旋转90°
     */
    private final float TEX_COORD_ROTATION_270[] = {
            // Bottom left.
            1.0f, 0.0f,
            // Bottom right.
            1.0f, 1.0f,
            // Top left.
            0.0f, 0.0f,
            // Top right.
            0.0f, 1.0f
    };

    private float TEX_COORD[] =  TEX_COORD_ROTATION_0;

    @Override
    public void init(Context context) {
        super.init(context);
    }

    @Override
    protected void initVbo() {

        mGLCubeBuffer = ByteBuffer.allocateDirect(VEX_CUBE.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mGLCubeBuffer.put(VEX_CUBE).position(0);
        int mAngle = getRotateDeg();
        if (mAngle == Surface.ROTATION_270) {
            TEX_COORD = TEX_COORD_ROTATION_270;
        } else if (mAngle == Surface.ROTATION_90) {
            TEX_COORD = TEX_COORD_ROTATION_90;
        } else if (mAngle == Surface.ROTATION_0) {
            TEX_COORD = TEX_COORD_ROTATION_0;
        } else {
            TEX_COORD = TEX_COORD_ROTATION_0;
        }

        mGLTextureBuffer = ByteBuffer.allocateDirect(TEX_COORD.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mGLTextureBuffer.put(TEX_COORD).position(0);

        mGLCubeId = new int[1];
        mGLTextureCoordinateId = new int[1];

        GLES20.glGenBuffers(1, mGLCubeId, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mGLCubeId[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mGLCubeBuffer.capacity() * 4, mGLCubeBuffer, GLES20.GL_STATIC_DRAW);

        GLES20.glGenBuffers(1, mGLTextureCoordinateId, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mGLTextureCoordinateId[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mGLTextureBuffer.capacity() * 4, mGLTextureBuffer, GLES20.GL_STATIC_DRAW);
    }

    /**
     *
     * @return Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270
     */
    private int getRotateDeg() {
        try {
            return  ((Activity) getContext()).getWindowManager().getDefaultDisplay().getRotation();
        }catch (Exception e){
            e.printStackTrace();
        }
        return -1;
    }
}
