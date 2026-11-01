#include "Charset.hpp"

#include <fstream>
#include <sstream>

namespace ocr {
namespace {

int leadingSpaces(const std::string& line) {
    int count = 0;
    for (char ch : line) {
        if (ch != ' ') break;
        ++count;
    }
    return count;
}

std::string trim(const std::string& value) {
    size_t begin = 0;
    size_t end = value.size();
    while (begin < end && (value[begin] == ' ' || value[begin] == '\t' || value[begin] == '\r')) {
        ++begin;
    }
    while (end > begin && (value[end - 1] == ' ' || value[end - 1] == '\t' || value[end - 1] == '\r')) {
        --end;
    }
    return value.substr(begin, end - begin);
}

std::string parseScalar(const std::string& raw) {
    size_t begin = 0;
    while (begin < raw.size() && raw[begin] == ' ') ++begin;
    std::string value = raw.substr(begin);
    if (value.size() < 2) return value;

    if (value.front() == '\'' && value.back() == '\'') {
        std::string inner = value.substr(1, value.size() - 2);
        std::string unescaped;
        for (size_t i = 0; i < inner.size(); ++i) {
            if (inner[i] == '\'' && i + 1 < inner.size() && inner[i + 1] == '\'') {
                unescaped.push_back('\'');
                ++i;
            } else {
                unescaped.push_back(inner[i]);
            }
        }
        return unescaped;
    }

    if (value.front() == '"' && value.back() == '"') {
        std::string inner = value.substr(1, value.size() - 2);
        std::string unescaped;
        for (size_t i = 0; i < inner.size(); ++i) {
            if (inner[i] != '\\' || i + 1 >= inner.size()) {
                unescaped.push_back(inner[i]);
                continue;
            }
            const char next = inner[++i];
            switch (next) {
                case 'n': unescaped.push_back('\n'); break;
                case 'r': unescaped.push_back('\r'); break;
                case 't': unescaped.push_back('\t'); break;
                case '"': unescaped.push_back('"'); break;
                case '\\': unescaped.push_back('\\'); break;
                default: unescaped.push_back(next); break;
            }
        }
        return unescaped;
    }
    return value;
}

std::vector<std::string> splitLines(const std::string& content) {
    std::string normalized;
    normalized.reserve(content.size());
    for (size_t i = 0; i < content.size(); ++i) {
        if (content[i] == '\r') {
            if (i + 1 < content.size() && content[i + 1] == '\n') continue;
            normalized.push_back('\n');
        } else {
            normalized.push_back(content[i]);
        }
    }

    std::vector<std::string> lines;
    std::stringstream stream(normalized);
    std::string line;
    while (std::getline(stream, line)) {
        lines.push_back(line);
    }
    return lines;
}

}  // namespace

bool parseCharset(const std::string& yaml, std::vector<std::string>& out) {
    out.clear();
    const std::vector<std::string> lines = splitLines(yaml);

    int postProcessLine = -1;
    for (size_t i = 0; i < lines.size(); ++i) {
        if (trim(lines[i]) == "PostProcess:") {
            postProcessLine = static_cast<int>(i);
            break;
        }
    }
    if (postProcessLine < 0) return false;

    const int postProcessIndent = leadingSpaces(lines[static_cast<size_t>(postProcessLine)]);

    int dictLine = -1;
    for (size_t i = static_cast<size_t>(postProcessLine) + 1; i < lines.size(); ++i) {
        const std::string trimmed = trim(lines[i]);
        if (trimmed.empty() || trimmed[0] == '#') continue;
        const int indent = leadingSpaces(lines[i]);
        if (indent <= postProcessIndent) break;
        if (trimmed == "character_dict:") {
            dictLine = static_cast<int>(i);
            break;
        }
    }
    if (dictLine < 0) return false;

    const int dictIndent = leadingSpaces(lines[static_cast<size_t>(dictLine)]);
    for (size_t i = static_cast<size_t>(dictLine) + 1; i < lines.size(); ++i) {
        const std::string& line = lines[i];
        const int indent = leadingSpaces(line);
        const std::string content = line.substr(static_cast<size_t>(indent));
        const std::string keyLike = trim(content);
        if (keyLike.empty() || keyLike[0] == '#') continue;
        if (content.empty() || content[0] != '-') {
            if (indent <= dictIndent) break;
            continue;
        }
        out.push_back(parseScalar(content.substr(1)));
    }

    if (out.empty()) return false;
    if (out.back() != " ") out.emplace_back(" ");
    return true;
}

bool loadCharset(const std::string& path, std::vector<std::string>& out) {
    std::ifstream file(path, std::ios::binary);
    if (!file) return false;
    std::ostringstream buffer;
    buffer << file.rdbuf();
    return parseCharset(buffer.str(), out);
}

}  // namespace ocr
