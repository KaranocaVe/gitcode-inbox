export const byId = (id) => document.getElementById(id);
export const number = new Intl.NumberFormat("zh-CN");
export const dateTime = new Intl.DateTimeFormat("zh-CN", {
  month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23",
});
export const clock = new Intl.DateTimeFormat("zh-CN", {
  hour: "2-digit", minute: "2-digit", hourCycle: "h23",
});

export function formattedTime(value, formatter = dateTime) {
  const date = new Date(value);
  return value && !Number.isNaN(date.getTime()) ? formatter.format(date) : "";
}

export function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text != null) node.textContent = text;
  if (className) node.className = className;
  return node;
}

export function button(text, onClick, className = "button") {
  const node = element("button", text, className);
  node.type = "button";
  node.addEventListener("click", onClick);
  return node;
}

export function boxMessage(text, retry) {
  const box = element("div", null, "empty");
  box.append(element("p", text));
  if (retry) box.append(button("重试", retry));
  return box;
}

export function gitcodeLink(text, href) {
  if (!href) return null;
  let url;
  try {
    url = new URL(href);
  } catch {
    return null;
  }
  if (url.origin !== "https://gitcode.com" || url.username || url.password) return null;
  const link = element("a", text);
  link.href = url.href;
  link.target = "_blank";
  link.rel = "noopener noreferrer";
  return link;
}

export function notice(node, text, tone = "info") {
  const content = text || "";
  if (node.textContent !== content) node.textContent = content;
  node.hidden = !content;
  node.dataset.tone = tone;
}

export function errorText(error) {
  if (error.status === 429) {
    const wait = error.retryAfter;
    const subject = error.data?.error === "upstream_rate_limited" ? "GitCode" : "服务";
    return wait > 0 ? `${subject}请求频率达到上限，请在 ${Math.ceil(wait)} 秒后重试。` : `${subject}请求频率达到上限，请稍后重试。`;
  }
  const messages = {
    https_required: "请通过 HTTPS 打开收件箱。",
    invalid_csrf_token: "请刷新页面后重试。",
    invalid_key_name: "令牌名称须为 1–100 个字符。",
    admin_required: "此操作需要管理员账户。",
    session_required: "请在浏览器中登录后重试。",
  };
  if (messages[error.data?.error]) return messages[error.data.error];
  if (error instanceof TypeError && !error.status) return "暂时无法连接服务。";
  return error.message || "暂时无法连接服务。";
}
