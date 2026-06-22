#version 320 es
precision mediump float;

uniform sampler2D uTexture;
in vec2 vTexCoord;
out vec4 fragColor;

// 热成像 Ironbow 调色板
vec3 ironbow(float t) {
    vec3 c;
    if (t < 0.25) {
        c = vec3(0.0, 0.0, 0.5 + t * 4.0 * 2.0);
    } else if (t < 0.50) {
        c = vec3(0.0, (t - 0.25) * 4.0, 1.0);
    } else if (t < 0.75) {
        float s = (t - 0.50) * 4.0;
        c = vec3(s, 1.0, 1.0 - s);
    } else {
        float s = (t - 0.75) * 4.0;
        c = vec3(1.0, 1.0 - s * 0.5, s * 0.5);
    }
    return c;
}

void main() {
    vec4 color = texture(uTexture, vTexCoord);
    float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    fragColor = vec4(ironbow(lum), color.a);
}
