package com.seu.magicfilter.base.gpuimage;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import com.seu.magicfilter.utils.OpenGLUtils;

import net.ossrs.yasea.R;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 水印的Filter
 *
 * @author apple
 */
public class GPUWaterMarkFilter extends GPUImageFilter {

    private final float TEX_COORD[] = {
            // Bottom left.
            1.0f, 1.0f,
            // Bottom right.
            0.0f, 1.0f,
            // Top left.
            1.0f, 0.0f,
            // Top right.
            0.0f, 0.0f
    };

    private int mGLWaterMarkProgId;
    private int mGLWaterMarkPositionIndex;
    private int mGLWaterMarkInputImageTextureIndex;
    private int mGLWaterMarkTextureCoordinateIndex;

    private FloatBuffer mTextureBuffer;

    /**水印的放置位置和宽高*/
    private int x, y, w, h;
    /**水印图片的bitmap*/
    private Bitmap mBitmap;

    private int mBitmapWidth, mBitmapHeight;
    /** 图片纹理ID */
    private int mTexturesId = OpenGLUtils.NO_TEXTURE;

    private boolean mIsInitialized;

    @Override
    public void init(Context context) {
        super.init(context);
        loadWaterMarkSamplerShader();
    }

    @Override
    protected void onInitialized() {
        super.onInitialized();
        mTextureBuffer = ByteBuffer.allocateDirect(TEX_COORD.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTextureBuffer.put(TEX_COORD).position(0);
        mIsInitialized = true;
    }

    private void loadWaterMarkSamplerShader() {
        mGLWaterMarkProgId = OpenGLUtils.loadProgram(OpenGLUtils.readShaderFromRawResource(getContext(), R.raw.base_2d_vertex),
                OpenGLUtils.readShaderFromRawResource(getContext(), R.raw.base_2d_fragment));
        mGLWaterMarkPositionIndex = GLES20.glGetAttribLocation(mGLWaterMarkProgId, "position");
        mGLWaterMarkTextureCoordinateIndex = GLES20.glGetAttribLocation(mGLWaterMarkProgId,"inputTextureCoordinate");
        mGLWaterMarkInputImageTextureIndex = GLES20.glGetUniformLocation(mGLWaterMarkProgId, "inputImageTexture");
    }

    @Override
    public int onDrawFrame(int cameraTextureId) {
        int fboId = drawToFboTexture(cameraTextureId);
        drawOverlay();
        drawToScreen(fboId);
        return OpenGLUtils.ON_DRAWN;
    }

    @Override
    protected int drawToFboTexture(int textureId) {
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
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(mGLInputImageTextureIndex, 0);

        onDrawArraysPre();

        GLES20.glViewport(0, 0, mInputWidth, mInputHeight);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mGLFboId[0]);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, mOutputWidth, mOutputHeight);

        onDrawArraysAfter();

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

        GLES20.glDisableVertexAttribArray(mGLPositionIndex);
        GLES20.glDisableVertexAttribArray(mGLTextureCoordinateIndex);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        return mGLFboTexId[0];
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        GLES20.glDeleteTextures(1, new int[]{mTexturesId}, 0);
        mTexturesId = OpenGLUtils.NO_TEXTURE;
    }

    private void drawOverlay() {
        if (mTexturesId == OpenGLUtils.NO_TEXTURE) {
            return ;
        }
        GLES20.glViewport(x, y, w == 0 ? mBitmapWidth : w, h == 0 ? mBitmapHeight : h);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_COLOR, GLES20.GL_DST_ALPHA);

        GLES20.glUseProgram(mGLWaterMarkProgId);
        GLES20.glEnableVertexAttribArray(mGLWaterMarkPositionIndex);
        GLES20.glVertexAttribPointer(mGLWaterMarkPositionIndex, 2, GLES20.GL_FLOAT, false, 4 * 2, mGLCubeBuffer);

        GLES20.glEnableVertexAttribArray(mGLWaterMarkTextureCoordinateIndex);
        GLES20.glVertexAttribPointer(mGLWaterMarkTextureCoordinateIndex, 2, GLES20.GL_FLOAT, false, 4 * 2, mTextureBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexturesId);
        GLES20.glUniform1i(mGLWaterMarkInputImageTextureIndex, 1);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mGLFboId[0]);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glReadPixels(0, 0, mInputWidth, mInputHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mGLFboBuffer);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        GLES20.glDisableVertexAttribArray(mGLWaterMarkPositionIndex);
        GLES20.glDisableVertexAttribArray(mGLWaterMarkTextureCoordinateIndex);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glViewport(0, 0, mOutputWidth, mOutputHeight);
    }

    public void setPosition(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.w = width;
        this.h = height;
    }

    /**
     * 设置水印图片
     *
     * @param bitmap 水印图片
     */
    public void setBitmap(Bitmap bitmap){
        if(this.mBitmap != null){
            this.mBitmap.recycle();
        }
        this.mBitmap = bitmap;
        mBitmapWidth = mBitmap.getWidth();
        mBitmapHeight = mBitmap.getHeight();
        runOnDraw(new Runnable() {
            @Override
            public void run() {
                mTexturesId = OpenGLUtils.loadTexture(mBitmap, OpenGLUtils.NO_TEXTURE, false);
            }
        });
    }
}
