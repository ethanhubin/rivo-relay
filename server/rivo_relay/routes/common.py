"""Shared API errors and request validation; independent of HTTP transport."""

class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        self.status, self.code = status, code
        super().__init__(message)

def required(body: dict, key: str, limit: int = 200) -> str:
    value = body.get(key)
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        raise ApiError(400, "invalid_request", f"{key} is required")
    return value

def permission_options(body: dict) -> dict:
    options = {}
    for key, arg, allowed in (
        ('sandbox', 'sandbox', ('read-only', 'workspace-write', 'danger-full-access')),
        ('approvalPolicy', 'approval_policy', ('on-request', 'untrusted', 'never', 'on-failure', 'preset-request', 'preset-auto', 'preset-full')),
    ):
        value = body.get(key)
        if value is not None:
            if value not in allowed:
                raise ApiError(400, 'invalid_request', f'Invalid {key}')
            options[arg] = value
    return options

def native_input(body: dict) -> list[dict] | None:
    items = body.get("input")
    if items is None:
        return None
    if not isinstance(items, list) or not 1 <= len(items) <= 64:
        raise ApiError(400, "invalid_input", "input must contain 1–64 native items")
    if sum(isinstance(item, dict) and item.get('type') in ('image', 'localImage') for item in items) > 10:
        raise ApiError(400, 'invalid_input', '最多添加 10 张图片')
    for item in items:
        if not isinstance(item, dict):
            raise ApiError(400, "invalid_input", "Invalid native input item")
        kind = item.get("type")
        if kind == "text":
            required(item, "text", 200000)
        elif kind == "image":
            if not (isinstance(item.get("url"), str) and item["url"] or isinstance(item.get("fileId"), str) and item["fileId"]):
                raise ApiError(400, "invalid_input", "Image needs a URL or native file ID")
            if isinstance(item.get('url'), str):
                limit = ((20 * 1024 * 1024 + 2) // 3) * 4 + 64 if item['url'].startswith('data:') else 8192
                if len(item['url']) > limit:
                    raise ApiError(413, 'image_too_large', '图片数据或地址超过大小限制')
            if isinstance(item.get('fileId'), str) and len(item['fileId']) > 512:
                raise ApiError(400, 'invalid_input', 'Image file ID is too long')
        elif kind in {"localImage", "mention", "skill"}:
            required(item, "path", 4096)
            if kind != "localImage":
                required(item, "name", 1000)
        else:
            raise ApiError(400, "invalid_input", "Unknown native input type")
    return items
