#version 320 es
precision mediump float;

uniform sampler2D uTexture;
uniform vec2 uResolution;
in vec2 vTexCoord;
out vec4 fragColor;

void main() {
    vec2 texel = 1.0 / uResolution;

    // 3x3 邻域亮度采样
    float p00 = dot(texture(uTexture, vTexCoord + vec2(-texel.x, -texel.y)).rgb, vec3(0.299,0.587,0.114));
    float p01 = dot(texture(uTexture, vTexCoord + vec2(0.0,      -texel.y)).rgb, vec3(0.299,0.587,0.114));
    float p02 = dot(texture(uTexture, vTexCoord + vec2( texel.x, -texel.y)).rgb, vec3(0.299,0.587,0.114));
    float p10 = dot(texture(uTexture, vTexCoord + vec2(-texel.x,  0.0)).rgb,      vec3(0.299,0.587,0.114));
    float p12 = dot(texture(uTexture, vTexCoord + vec2( texel.x,  0.0)).rgb,      vec3(0.299,0.587,0.114));
    float p20 = dot(texture(uTexture, vTexCoord + vec2(-texel.x,  texel.y)).rgb, vec3(0.299,0.587,0.114));
    float p21 = dot(texture(uTexture, vTexCoord + vec2(0.0,       texel.y)).rgb, vec3(0.299,0.587,0.114));
    float p22 = dot(texture(uTexture, vTexCoord + vec2( texel.x,  texel.y)).rgb, vec3(0.299,0.587,0.114));

    // Sobel
    float gx = -p00 - 2.0*p10 - p20 + p02 + 2.0*p12 + p22;
    float gy = -p00 - 2.0*p01 - p02 + p20 + 2.0*p21 + p22;
    float edge = sqrt(gx*gx + gy*gy);
    edge = clamp(edge, 0.0, 1.0);
    fragColor = vec4(vec3(edge), 1.0);
}
