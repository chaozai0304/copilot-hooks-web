export function fmtTime(value?: string) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}

export function fmtDuration(ms?: number) {
  if (ms == null) return '-';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(ms / 60000);
  const s = Math.floor((ms % 60000) / 1000);
  return `${m}m${s}s`;
}

export function fmtNum(value?: number) {
  return value == null ? '-' : Number(value).toLocaleString('zh-CN');
}

export function toIsoOrNull(value: string, neverExpire: boolean) {
  return neverExpire || !value ? null : new Date(value).toISOString();
}
