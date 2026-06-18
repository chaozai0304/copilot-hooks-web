import { EmptyState } from '../components/common/EmptyState';

interface PlaceholderPageProps {
  title: string;
  description: string;
}

export function PlaceholderPage({ title, description }: PlaceholderPageProps) {
  return (
    <div className="card page-card">
      <div className="card-head">{title}</div>
      <EmptyState title="模块已预留" description={description} />
    </div>
  );
}
