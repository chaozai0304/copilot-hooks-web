interface StatusBadgeProps {
  tone?: 'ok' | 'bad' | 'warn' | 'muted';
  children: React.ReactNode;
}

export function StatusBadge({ tone = 'muted', children }: StatusBadgeProps) {
  return <span className={`status-badge status-${tone}`}>{children}</span>;
}
