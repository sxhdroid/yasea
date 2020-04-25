attribute vec4 position;
attribute vec2 inputTextureCoordinate;

varying vec2 textureCoordinate;

void main() {
    //OpenGL纹理系统坐标 与 Android图像坐标 Y轴是颠倒的。这里旋转过来
    textureCoordinate = vec2(inputTextureCoordinate.x, 1.0 - inputTextureCoordinate.y);
    gl_Position = position;
}