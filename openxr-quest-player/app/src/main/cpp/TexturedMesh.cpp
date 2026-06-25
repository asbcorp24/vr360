#include "TexturedMesh.h"

#include <cassert>
#include <cstring>
#include <limits>
#include <utility>

TexturedMesh::TexturedMesh() = default;

TexturedMesh::TexturedMesh(
    GLenum mode,
    GLsizei indexCount,
    std::unique_ptr<GLfloat[]> positions,
    std::unique_ptr<GLfloat[]> uvs,
    std::unique_ptr<GLushort[]> indices
) : mode_(mode),
    indexCount_(indexCount),
    positions_(std::move(positions)),
    uvs_(std::move(uvs)),
    indices_(std::move(indices)) {}

void TexturedMesh::render(GLint positionLocation, GLint uvLocation) const {
    if (indexCount_ == 0) {
        return;
    }

    glEnableVertexAttribArray(positionLocation);
    glVertexAttribPointer(positionLocation, 3, GL_FLOAT, GL_FALSE, 0, positions_.get());
    glEnableVertexAttribArray(uvLocation);
    glVertexAttribPointer(uvLocation, 2, GL_FLOAT, GL_FALSE, 0, uvs_.get());
    glDrawElements(mode_, indexCount_, GL_UNSIGNED_SHORT, indices_.get());
}

GLushort TexturedMesh::Builder::addVertex(float x, float y, float z, float u, float v) {
    const std::size_t size = positions_.size();
    assert((size % 3) == 0);
    assert((size / 3) <= std::numeric_limits<GLushort>::max());

    const auto index = static_cast<GLushort>(size / 3);
    positions_.push_back(x);
    positions_.push_back(y);
    positions_.push_back(z);
    uvs_.push_back(u);
    uvs_.push_back(v);
    return index;
}

void TexturedMesh::Builder::addTriangle(GLushort a, GLushort b, GLushort c) {
    indices_.push_back(a);
    indices_.push_back(b);
    indices_.push_back(c);
}

void TexturedMesh::Builder::addQuad(GLushort a, GLushort b, GLushort c, GLushort d) {
    indices_.push_back(a);
    indices_.push_back(c);
    indices_.push_back(b);
    indices_.push_back(a);
    indices_.push_back(d);
    indices_.push_back(c);
}

TexturedMesh TexturedMesh::Builder::build() {
    assert(indices_.size() <= std::numeric_limits<GLsizei>::max());

    auto positions = std::make_unique<GLfloat[]>(positions_.size());
    std::memcpy(positions.get(), positions_.data(), positions_.size() * sizeof(GLfloat));

    auto uvs = std::make_unique<GLfloat[]>(uvs_.size());
    std::memcpy(uvs.get(), uvs_.data(), uvs_.size() * sizeof(GLfloat));

    auto indices = std::make_unique<GLushort[]>(indices_.size());
    std::memcpy(indices.get(), indices_.data(), indices_.size() * sizeof(GLushort));

    return TexturedMesh(
        GL_TRIANGLES,
        static_cast<GLsizei>(indices_.size()),
        std::move(positions),
        std::move(uvs),
        std::move(indices)
    );
}
