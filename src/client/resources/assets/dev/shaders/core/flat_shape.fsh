#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// Плоская заливка по цвету вершин: форму задаёт сама геометрия, а не расстояние в шейдере.
// Так рисуются фигуры, которых нет среди прямоугольников и кругов — например дуги колеса.
void main() {
    if (vertexColor.a <= 0.001) {
        discard;
    }

    fragColor = vertexColor * ColorModulator;
}
