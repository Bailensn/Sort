#ifndef ENGINE_ERRORS_H
#define ENGINE_ERRORS_H

/* 所有 C ABI 入口共用的返回码：0 成功，负数表示错误。 */
#define ENGINE_OK              0
#define ENGINE_ERR_NULL       -1
#define ENGINE_ERR_BAD_INPUT  -2
#define ENGINE_ERR_EXCEPTION  -3
#define ENGINE_ERR_NO_BUFFER  -4  /* buf 太小，*outLen 已被写入所需长度 */

#endif /* ENGINE_ERRORS_H */
