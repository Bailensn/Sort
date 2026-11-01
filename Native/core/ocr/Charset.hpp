#pragma once

#include <string>
#include <vector>

namespace ocr {

/// 从 rec 的 inference.yml 里取出 character_dict，末尾补一个空格（CTC 的 blank 之外那个）。
bool parseCharset(const std::string& yaml, std::vector<std::string>& out);

/// 从文件读并解析，失败返回 false。
bool loadCharset(const std::string& path, std::vector<std::string>& out);

}  // namespace ocr
