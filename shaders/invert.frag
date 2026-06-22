#version 320 es
precision mediump float;

uniform sampler2D uTexture;
in vec2 vTexCoord;
out vec4 fragColor;

void main() {
    vec4 color = texture(uTexture, vTexCoord);
    fragColor = vec4(1.0 - color.rgb, color.a);
}
