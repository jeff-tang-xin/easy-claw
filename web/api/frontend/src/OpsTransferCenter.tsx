// 文件任务中心：上传/下载任务列表，进度条 + 状态 + 删除记录。
// 参照 Workbench 风格浮窗；运行中任务显示实时进度，失败显示错误信息。
import {useState} from 'react';
import {TransferTask} from './opsTransfers';

interface Props {
  tasks: TransferTask[];
  onRemove: (id: number) => void;
  onClose: () => void;
}

const fmtSize = (n: number) => {
  if (n >= 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' M';
  if (n >= 1024) return (n / 1024).toFixed(1) + ' K';
  return n + ' B';
};

export default function OpsTransferCenter({tasks, onRemove, onClose}: Props) {
  const [tab, setTab] = useState<'upload' | 'download'>('upload');
  const list = tasks.filter((t) => t.direction === tab);
  return (
    <div className="ops-tc">
      <div className="ops-tc-head">
        <span>文件任务中心</span>
        <button className="ops-tc-close" onClick={onClose}>×</button>
      </div>
      <div className="ops-tc-tabs">
        <button className={tab === 'upload' ? 'active' : ''} onClick={() => setTab('upload')}>上传任务</button>
        <button className={tab === 'download' ? 'active' : ''} onClick={() => setTab('download')}>下载任务</button>
      </div>
      <div className="ops-tc-list">
        {list.length === 0 && <div className="ops-tc-empty">暂无任务</div>}
        {list.map((t) => {
          const pct = t.size ? Math.min(100, Math.round((t.loaded / t.size) * 100)) : 0;
          return (
            <div key={t.id} className="ops-tc-item">
              <div className="ops-tc-info">
                <span className="ops-tc-name" title={t.name}>{t.name}</span>
                <span className="ops-tc-size">{fmtSize(t.loaded)}{t.size ? ` / ${fmtSize(t.size)}` : ''}</span>
                <span className={'ops-tc-state ' + t.status}>
                  {t.status === 'done' ? '✓' : t.status === 'error' ? '✗' : ''}
                </span>
                <button className="ops-tc-del" onClick={() => onRemove(t.id)}>删除</button>
              </div>
              <div className="ops-tc-bar">
                <div className="ops-tc-bar-fill" style={{width: pct + '%'}} />
              </div>
              {t.status === 'error' && <div className="ops-tc-err" title={t.message}>{t.message}</div>}
            </div>
          );
        })}
      </div>
    </div>
  );
}
