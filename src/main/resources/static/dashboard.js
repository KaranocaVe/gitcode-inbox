import {element, button, boxMessage, errorText, formattedTime, number} from "./ui.js";

const ranges = [[6, "近 6 小时"], [24, "近 24 小时"], [168, "近 7 天"]];
const charts = [
  {title: "同步次数", series: [
    {key: "sync_success", label: "成功", color: "#005c4e"},
    {key: "sync_failure", label: "失败", color: "#8e362a", dash: [5, 4]},
    {key: "sync_debounced", label: "去重", color: "#535e54", dash: [2, 4]},
  ]},
  {title: "请求次数", series: [
    {key: "upstream_requests", label: "GitCode 请求", color: "#005c4e"},
    {key: "upstream_rate_limited", label: "GitCode 限流", color: "#8e362a", dash: [5, 4]},
    {key: "local_rate_limited", label: "本服务限流", color: "#535e54", dash: [2, 4]},
  ]},
  {title: "确认已读 · 条", series: [
    {key: "read_confirmed", label: "确认已读", color: "#005c4e"},
  ]},
  {title: "标记失败 · 次", series: [
    {key: "read_failure", label: "标记失败", color: "#8e362a", dash: [5, 4]},
  ]},
];
const instances = new WeakMap();
const hourLabel = new Intl.DateTimeFormat("zh-CN", {month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit"});
const dayLabel = new Intl.DateTimeFormat("zh-CN", {month: "numeric", day: "numeric"});
const axisClock = new Intl.DateTimeFormat("zh-CN", {hour: "2-digit", minute: "2-digit", hourCycle: "h23"});
const compactNumber = new Intl.NumberFormat("zh-CN", {notation: "compact", maximumFractionDigits: 1});

function metric(label, count, unit = "") {
  const definition = element("dl", null, "sheet metric");
  const value = element("dd", count == null ? "—" : number.format(count), "metric-value");
  if (unit && count != null) value.append(element("small", unit));
  definition.append(element("dt", label), value);
  return definition;
}

function historyChart(config, samples, lengthHours, onSelect) {
  const chart = element("figure", null, "chart");
  const canvas = element("canvas");
  canvas.setAttribute("aria-hidden", "true");
  const legend = element("figcaption", null, "legend");
  const readings = [];
  for (const series of config.series) {
    const label = element("span");
    const sample = element("i", null, "legend-line " + series.key);
    sample.setAttribute("aria-hidden", "true");
    const reading = element("strong");
    label.append(sample, series.label, reading);
    legend.append(label);
    readings.push([series.key, reading]);
  }
  chart.append(element("h3", config.title), canvas, legend);

  const axisPadding = 42;
  const timeMin = samples[0].at;
  const timeMax = Math.max(timeMin + 1, samples.at(-1).at);
  const ratio = (sample) => samples.length === 1 ? 0.5 : (sample.at - timeMin) / (timeMax - timeMin);
  const max = Math.max(1, ...samples.flatMap(({values}) => config.series.map(({key}) => values[key] ?? 0)));
  const scale = 4 * Math.ceil(max / 4);
  let selectedIndex = samples.length - 1;

  function draw(index) {
    selectedIndex = index;
    for (const [key, reading] of readings) {
      const value = samples[index].values[key];
      reading.textContent = value == null ? "—" : number.format(value);
    }
    const {width, height} = canvas.getBoundingClientRect();
    if (!width || !height) return;
    const pixelRatio = window.devicePixelRatio || 1;
    canvas.width = Math.round(width * pixelRatio);
    canvas.height = Math.round(height * pixelRatio);
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    ctx.scale(pixelRatio, pixelRatio);
    const left = axisPadding;
    const right = width - 16;
    const top = 13;
    const bottom = height - 27;
    const toX = (sample) => left + (right - left) * ratio(sample);
    const toY = (value) => bottom - (bottom - top) * (value / scale);
    ctx.font = "12px system-ui";
    ctx.fillStyle = "#535e54";
    ctx.textAlign = "right";
    for (let i = 0; i <= 4; i++) {
      const value = (i * scale) / 4;
      const y = toY(value);
      ctx.strokeStyle = "#dce0d9";
      ctx.beginPath();
      ctx.moveTo(left, y);
      ctx.lineTo(right, y);
      ctx.stroke();
      const label = value >= 1000 ? compactNumber.format(value) : number.format(value);
      ctx.fillText(label, left - 6, y + 4);
    }
    const labelFormat = lengthHours > 24 ? dayLabel : axisClock;
    if (samples.length > 1) {
      const tickCount = Math.min(samples.length, width > 555 ? 5 : 3);
      for (let i = 0; i < tickCount; i++) {
        const sample = samples[Math.round(((samples.length - 1) * i) / (tickCount - 1))];
        ctx.textAlign = i === 0 ? "left" : i === tickCount - 1 ? "right" : "center";
        ctx.fillText(labelFormat.format(sample.at), toX(sample), height - 7);
      }
    } else {
      ctx.textAlign = "center";
      ctx.fillText(labelFormat.format(timeMin), toX(samples[0]), height - 7);
    }
    ctx.lineWidth = 1;
    ctx.strokeStyle = "#7c867c";
    ctx.setLineDash([2, 3]);
    ctx.beginPath();
    ctx.moveTo(toX(samples[index]), top);
    ctx.lineTo(toX(samples[index]), bottom);
    ctx.stroke();
    for (const series of config.series) {
      ctx.strokeStyle = series.color;
      ctx.lineWidth = 2;
      ctx.setLineDash(series.dash || []);
      ctx.beginPath();
      let started = false;
      for (const sample of samples) {
        const value = sample.values[series.key];
        if (value == null) {
          started = false;
          continue;
        }
        if (started) ctx.lineTo(toX(sample), toY(value));
        else ctx.moveTo(toX(sample), toY(value));
        started = true;
      }
      ctx.stroke();
      const value = samples[index].values[series.key];
      if (value != null) {
        ctx.setLineDash([]);
        ctx.fillStyle = series.color;
        ctx.beginPath();
        ctx.arc(toX(samples[index]), toY(value), 3.5, 0, 2 * Math.PI);
        ctx.fill();
      }
    }
  }

  function choose(event) {
    const bounds = canvas.getBoundingClientRect();
    const share = (event.clientX - bounds.left - axisPadding) / (bounds.width - axisPadding - 16);
    let nearest = 0;
    for (let i = 1; i < samples.length; i++) {
      if (Math.abs(ratio(samples[i]) - share) < Math.abs(ratio(samples[nearest]) - share)) nearest = i;
    }
    if (nearest !== selectedIndex) onSelect(nearest);
  }
  canvas.addEventListener("pointermove", (event) => {
    if (event.pointerType !== "touch") choose(event);
  });
  canvas.addEventListener("click", choose);
  const observer = new ResizeObserver(() => draw(selectedIndex));
  observer.observe(canvas);
  return {chart, draw, disconnect: () => observer.disconnect()};
}

function renderMetrics(target, data) {
  const totals = data.totals || {};
  const latency = data.sync_latency_ms || {};
  const cards = element("div", null, "metrics");
  cards.append(
    metric("当前未读通知", data.current?.unread_total),
    metric("同步成功", totals.sync_success, "次"),
    metric("GitCode 请求", totals.upstream_requests, "次"),
    metric("同步耗时 P95（估计）", latency.p95, "ms"),
  );
  const period = element("p", `近 ${data.hours} 小时 · 趋势按浏览器时区显示`, "chart-period");
  const samples = (data.series || [])
    .map((values) => ({values, at: Date.parse(values.bucket)}))
    .filter(({at}) => Number.isFinite(at))
    .sort((a, b) => a.at - b.at);
  target.replaceChildren(cards, period);
  if (!samples.length) {
    target.append(element("p", "这段时间没有记录。", "chart-empty"));
    return () => {};
  }
  const detail = element("div", null, "chart-detail");
  const label = element("label", "查看时段");
  const selector = element("select");
  samples.forEach(({at}, index) => {
    const option = element("option", formattedTime(at, hourLabel));
    option.value = String(index);
    selector.append(option);
  });
  label.append(selector);
  detail.append(label);
  let current = -1;
  function onSelect(index) {
    if (current === index) return;
    current = index;
    selector.value = String(index);
    for (const graph of plots) graph.draw(index);
  }
  const plots = charts.map((config) => historyChart(config, samples, data.hours, onSelect));
  target.append(detail, ...plots.map(({chart}) => chart));
  selector.addEventListener("change", () => onSelect(Number(selector.value)));
  onSelect(samples.length - 1);
  return () => plots.forEach(({disconnect}) => disconnect());
}

export function loadDashboard(host, api) {
  instances.get(host)?.();
  let abort;
  let cleanup = () => {};
  const sheet = element("section", null, "sheet");
  const heading = element("div", null, "chart-head");
  const choices = element("div", null, "range-group");
  const holder = element("div");
  choices.setAttribute("aria-label", "统计范围");
  heading.append(element("h2", "运行趋势"), choices);
  sheet.append(heading, holder);
  host.replaceChildren(sheet);

  async function load(hours) {
    abort?.abort();
    cleanup();
    const controller = new AbortController();
    abort = controller;
    for (const choice of choices.children) choice.setAttribute("aria-pressed", String(Number(choice.value) === hours));
    holder.setAttribute("aria-busy", "true");
    holder.replaceChildren(boxMessage("正在读取运行指标…"));
    try {
      const data = await api("/admin/metrics?hours=" + hours, {signal: controller.signal});
      if (!controller.signal.aborted && sheet.isConnected) cleanup = renderMetrics(holder, data);
    } catch (error) {
      if (error.name !== "AbortError" && sheet.isConnected) holder.replaceChildren(boxMessage(errorText(error), () => load(hours)));
    } finally {
      if (abort === controller) holder.setAttribute("aria-busy", "false");
    }
  }
  for (const [hours, label] of ranges) {
    const choice = button(label, () => load(hours));
    choice.value = String(hours);
    choices.append(choice);
  }
  const destroy = () => {
    abort?.abort();
    cleanup();
  };
  instances.set(host, destroy);
  load(24);
}

export function closeDashboard(host) {
  instances.get(host)?.();
  instances.delete(host);
  host.replaceChildren();
}
