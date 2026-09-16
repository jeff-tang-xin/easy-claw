import React from 'react';

interface Props {
  title: string;
  subtitle?: string;
  onClose: () => void;
  children: React.ReactNode;
  width?: number;
}

/** 轻量弹窗：遮罩点击关闭，交互模式与主前端 Modal 一致。 */
export default function Modal({title, subtitle, onClose, children, width}: Props) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" style={width ? {width} : undefined} onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <div>
            <h3>{title}</h3>
            {subtitle && <div className="modal-sub">{subtitle}</div>}
          </div>
          <button type="button" className="btn btn-ghost btn-sm" onClick={onClose} title="关闭">
            ✕
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}
