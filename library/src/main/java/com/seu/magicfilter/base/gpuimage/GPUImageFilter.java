/*
 * Copyright (C) 2012 CyberAgent
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.seu.magicfilter.base.gpuimage;

import android.app.Activity;
import android.content.Context;
import android.graphics.PointF;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

import com.seu.magicfilter.utils.MagicFilterType;
import com.seu.magicfilter.utils.OpenGLUtils;

import net.ossrs.yasea.R;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.LinkedList;

public class GPUImageFilter {

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

    protected final float TEX_COORD_ROTATION_0[] = {
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
     * 在0°基础上顺时针旋转270°
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

    /** 最终使用的纹理矩阵*/
    private float TEX_COORD_ROTATION[];

    private boolean mIsInitialized;
    private WeakReference<Context> mContext;
    private MagicFilterType mType;
    private final LinkedList<Runnable> mRunOnDraw;
    private final int mVertexShaderId;
    private final int mFragmentShaderId;

    int mGLProgId;
    int mGLPositionIndex;
    int mGLInputImageTextureIndex;
    int mGLTextureCoordinateIndex;
    int mGLTextureTransformIndex;

    protected int mInputWidth;
    protected int mInputHeight;
    protected int mOutputWidth;
    protected int mOutputHeight;
    protected FloatBuffer mGLCubeBuffer;
    protected FloatBuffer mGLTextureBuffer;

    int[] mGLCubeId;
    int[] mGLTextureCoordinateId;
    float[] mGLTextureTransformMatrix;

    int[] mGLFboId;
    int[] mGLFboTexId;
    IntBuffer mGLFboBuffer;

    public GPUImageFilter() {
        this(MagicFilterType.NONE);
    }

    public GPUImageFilter(MagicFilterType type) {
        this(type, R.raw.vertex, R.raw.fragment);
    }

    public GPUImageFilter(MagicFilterType type, int fragmentShaderId) {
        this(type, R.raw.vertex, fragmentShaderId);
    }

    public GPUImageFilter(MagicFilterType type, int vertexShaderId, int fragmentShaderId) {
        mType = type;
        mRunOnDraw = new LinkedList<>();
        mVertexShaderId = vertexShaderId;
        mFragmentShaderId = fragmentShaderId;
    }

    public void init(Context context) {
        mContext = new WeakReference<>(context);
        onInit();
        onInitialized();
    }

    protected void onInit() {
        initVbo();
        loadSamplerShader();
    }

    protected void onInitialized() {
        mIsInitialized = true;
    }

    public final void destroy() {
        mIsInitialized = false;
        destroyFboTexture();
        destoryVbo();
        GLES20.glDeleteProgram(mGLProgId);
        onDestroy();
    }

    protected void onDestroy() {
    }

    public void onInputSizeChanged(final int width, final int height) {
        mInputWidth = width;
        mInputHeight = height;
        initFboTexture(width, height);
    }

    public void onDisplaySizeChanged(final int width, final int height) {
        mOutputWidth = width;
        mOutputHeight = height;
    }

    private void loadSamplerShader() {
        mGLProgId = OpenGLUtils.loadProgram(OpenGLUtils.readShaderFromRawResource(getContext(), mVertexShaderId),
            OpenGLUtils.readShaderFromRawResource(getContext(), mFragmentShaderId));
        mGLPositionIndex = GLES20.glGetAttribLocation(mGLProgId, "position");
        mGLTextureCoordinateIndex = GLES20.glGetAttribLocation(mGLProgId,"inputTextureCoordinate");
        mGLTextureTransformIndex = GLES20.glGetUniformLocation(mGLProgId, "textureTransform");
        mGLInputImageTextureIndex = GLES20.glGetUniformLocation(mGLProgId, "inputImageTexture");
    }

    private void initVbo() {
        mGLCubeBuffer = ByteBuffer.allocateDirect(VEX_CUBE.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mGLCubeBuffer.put(VEX_CUBE).position(0);
        int mAngle = getRotateDeg();
        if (mAngle == Surface.ROTATION_270) {
            TEX_COORD_ROTATION = TEX_COORD_ROTATION_270;
        } else if (mAngle == Surface.ROTATION_90) {
            TEX_COORD_ROTATION = TEX_COORD_ROTATION_90;
        }  else {
            TEX_COORD_ROTATION = TEX_COORD_ROTATION_0;
        }

        mGLTextureBuffer = ByteBuffer.allocateDirect(TEX_COORD_ROTATION.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mGLTextureBuffer.put(TEX_COORD_ROTATION).position(0);

        mGLCubeId = new int[1];
        mGLTextureCoordinateId = new int[1];

        GLES20.glGenBuffers(1, mGLCubeId, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mGLCubeId[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mGLCubeBuffer.capacity() * 4, mGLCubeBuffer, GLES20.GL_STATIC_DRAW);

        GLES20.glGenBuffers(1, mGLTextureCoordinateId, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mGLTextureCoordinateId[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mGLTextureBuffer.capacity() * 4, mGLTextureBuffer, GLES20.GL_STATIC_DRAW);
    }

    private void destoryVbo() {
        if (mGLCubeId != null) {
            GLES20.glDeleteBuffers(1, mGLCubeId, 0);
            mGLCubeId = null;
        }
        if (mGLTextureCoordinateId != null) {
            GLES20.glDeleteBuffers(1, mGLTextureCoordinateId, 0);
            mGLTextureCoordinateId = null;
        }
    }

    private void initFboTexture(int width, int height) {
        if (width == 0 || height == 0) {
            return;
        }
        if (mGLFboId != null && (mInputWidth != width || mInputHeight != height)) {
            destroyFboTexture();
        }

        if (mGLFboBuffer != null) {
            return;
        }
        mGLFboBuffer = IntBuffer.allocate(width * height);

        mGLFboId = new int[1];
        mGLFboTexId = new int[1];

        GLES20.glGenFramebuffers(1, mGLFboId, 0);
        GLES20.glGenTextures(1, mGLFboTexId, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mGLFboTexId[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mGLFboId[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, mGLFboTexId[0], 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void destroyFboTexture() {
        if (mGLFboTexId != null) {
            GLES20.glDeleteTextures(1, mGLFboTexId, 0);
            mGLFboTexId = null;
        }
        if (mGLFboId != null) {
            GLES20.glDeleteFramebuffers(1, mGLFboId, 0);
            mGLFboId = null;
        }
        mGLFboBuffer = null;
    }

    public int onDrawFrame(final int textureId, final FloatBuffer cubeBuffer, final FloatBuffer textureBuffer) {
        if (!mIsInitialized) {
            return OpenGLUtils.NOT_INIT;
        }

        GLES20.glUseProgram(mGLProgId);
        runPendingOnDrawTasks();

        GLES20.glEnableVertexAttribArray(mGLPositionIndex);
        GLES20.glVertexAttribPointer(mGLPositionIndex, 2, GLES20.GL_FLOAT, false, 4 * 2, cubeBuffer);

        GLES20.glEnableVertexAttribArray(mGLTextureCoordinateIndex);
        GLES20.glVertexAttribPointer(mGLTextureCoordinateIndex, 2, GLES20.GL_FLOAT, false, 4 * 2, textureBuffer);

        if (textureId != OpenGLUtils.NO_TEXTURE) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glUniform1i(mGLInputImageTextureIndex, 0);
        }

        onDrawArraysPre();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        onDrawArraysAfter();

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        GLES20.glDisableVertexAttribArray(mGLPositionIndex);
        GLES20.glDisableVertexAttribArray(mGLTextureCoordinateIndex);

        return OpenGLUtils.ON_DRAWN;
    }

    public int onDrawFrame(int cameraTextureId) {
        if (!mIsInitialized) {
            return OpenGLUtils.NOT_INIT;
        }

        if (mGLFboId == null) {
            return OpenGLUtils.NO_TEXTURE;
        }

        GLES20.glUseProgram(mGLProgId);
        runPendingOnDrawTasks();

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mGLCubeId[0]);
        GLES20.glEnableVertexAttribArray(mGLPositionIndex);
        GLES20.glVertexAttribPointer(mGLPositionIndex, 2, GLES20.GL_FLOAT, false, 4 * 2, 0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mGLTextureCoordinateId[0]);
        GLES20.glEnableVertexAttribArray(mGLTextureCoordinateIndex);
        GLES20.glVertexAttribPointer(mGLTextureCoordinateIndex, 2, GLES20.GL_FLOAT, false, 4 * 2, 0);

        GLES20.glUniformMatrix4fv(mGLTextureTransformIndex, 1, false, mGLTextureTransformMatrix, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(mGLInputImageTextureIndex, 0);

        onDrawArraysPre();

        GLES20.glViewport(0, 0, mInputWidth, mInputHeight);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mGLFboId[0]);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glReadPixels(0, 0, mInputWidth, mInputHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mGLFboBuffer);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, mOutputWidth, mOutputHeight);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        onDrawArraysAfter();

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

        GLES20.glDisableVertexAttribArray(mGLPositionIndex);
        GLES20.glDisableVertexAttribArray(mGLTextureCoordinateIndex);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        return mGLFboTexId[0];
    }

    protected void onDrawArraysPre() {}

    protected void onDrawArraysAfter() {}
    
    protected void runPendingOnDrawTasks() {
        while (!mRunOnDraw.isEmpty()) {
            mRunOnDraw.removeFirst().run();
        }
    }
    
    public int getProgram() {
        return mGLProgId;
    }

    public IntBuffer getGLFboBuffer() {
        return mGLFboBuffer;
    }

    protected Context getContext() {
        if (mContext != null) {
            return mContext.get();
        }
        return null;
    }

    protected MagicFilterType getFilterType() {
        return mType;
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

    public void setTextureTransformMatrix(float[] mtx){
        mGLTextureTransformMatrix = mtx;
    }

    protected void setInteger(final int location, final int intValue) {
        runOnDraw(new Runnable() {
            @Override
            public void run() {
                GLES20.glUniform1i(location, intValue);
            }
        });
    }

    protected void setFloat(final int location, final float floatValue) {
        runOnDraw(new Runnable() {
            @Override
            public void run() {
                GLES20.glUniform1f(location, floatValue);
            }
        });
    }

    protected void setFloatVec2(final int location, final float[] arrayValue) {
        runOnDraw(new Runnable() {
            @Override
            public void run() {
                GLES20.glUniform2fv(location, 1, FloatBuffer.wrap(arrayValue));
            }
        });
    }

    protected void setFloatVec3(final int location, final float[] arrayValue) {
        runOnDraw(new Runnable() {
            @Override
            public void run() {
                GLES20.glUniform3fv(location, 1, FloatBuffer.wrap(arrayValue));
            }
        });
    }

    protected void setFloatVec4(final int location, final float[] arrayValue) {
        runOnDraw(new Runnable() {
            @Override
            public void run() {
                GLES20.glUniform4fv(location, 1, FloatBuffer.wrap(arrayValue));
            }
        });
    }

    protected void setFloatArray(final int location, final float[] arrayValue) {
        runOnDraw(new Runnable() {
            @Override
            public void run() {
                GLES20.glUniform1fv(location, arrayValue.length, FloatBuffer.wrap(arrayValue));
            }
        });
    }

    protected void setPoint(final int location, final PointF point) {
        runOnDraw(new Runnable() {

            @Override
            public void run() {
                float[] vec2 = new float[2];
                vec2[0] = point.x;
                vec2[1] = point.y;
                GLES20.glUniform2fv(location, 1, vec2, 0);
            }
        });
    }

    protected void setUniformMatrix3f(final int location, final float[] matrix) {
        runOnDraw(new Runnable() {

            @Override
            public void run() {
                GLES20.glUniformMatrix3fv(location, 1, false, matrix, 0);
            }
        });
    }

    protected void setUniformMatrix4f(final int location, final float[] matrix) {
        runOnDraw(new Runnable() {

            @Override
            public void run() {
                GLES20.glUniformMatrix4fv(location, 1, false, matrix, 0);
            }
        });
    }

    protected void runOnDraw(final Runnable runnable) {
        synchronized (mRunOnDraw) {
            mRunOnDraw.addLast(runnable);
        }
    }
}

