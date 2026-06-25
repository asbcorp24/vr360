#pragma once

#include <GLES3/gl3.h>

#include <memory>
#include <vector>

class TexturedMesh {
public:
    TexturedMesh();
    TexturedMesh(
        GLenum mode,
        GLsizei indexCount,
        std::unique_ptr<GLfloat[]> positions,
        std::unique_ptr<GLfloat[]> uvs,
        std::unique_ptr<GLushort[]> indices
    );

    void render(GLint positionLocation, GLint uvLocation) const;

    class Builder {
    public:
        GLushort addVertex(float x, float y, float z, float u, float v);
        void addTriangle(GLushort a, GLushort b, GLushort c);
        void addQuad(GLushort a, GLushort b, GLushort c, GLushort d);
        TexturedMesh build();

    private:
        std::vector<GLfloat> positions_;
        std::vector<GLfloat> uvs_;
        std::vector<GLushort> indices_;
    };

private:
    GLenum mode_ = GL_TRIANGLES;
    GLsizei indexCount_ = 0;
    std::unique_ptr<GLfloat[]> positions_;
    std::unique_ptr<GLfloat[]> uvs_;
    std::unique_ptr<GLushort[]> indices_;
};
