attribute vec4 position;
attribute vec2 inputTextureCoordinate;

varying vec2 textureCoordinate;

void main() {
    textureCoordinate = inputTextureCoordinate.xy;
    gl_Position = position;
}