import {useCallback, useEffect, useState} from 'react';
import {createModelCatalog, deleteModelCatalog, listModelCatalog, updateModelCatalog} from '../api';
import {loadSession} from '../auth';
import Modal from '../components/Modal';
import type {MeResponse, ModelCatalogDto} from '../types';

/**
 * 模型目录维护（platformAdmin）：平台级模型清单与积分比例。
 * provider 的 models 清单按名称引用目录模型；网关按请求模型名取比例扣积分，
 * 未登记模型默认 1 分/次。creditCost 支持 1 位小数（服务端舍弃多余位数不进位）。
 */
export default function ModelCatalogPage({me}: {me: MeResponse}) {
  const [rows, setRows] = useState<ModelCatalogDto[] | null>(null);
  const [pageError, setPageError] = useState('');
  const [editing, setEditing] = useState<ModelCatalogDto | null>(null);
  const [creating, setCreating] = useState(false);
  const [modelName, setModelName] = useState('');
  const [creditCost, setCreditCost] = useState('1');
  const [remark, setRemark] = useState('');
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    try {
      setRows(await listModelCatalog());
    } catch (err) {
      setPageError(err instanceof Error ? err.message : '加载模型目录失败');
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const openCreate = () => {
    setEditing(null);
    setCreating(true);
    setModelName('');
    setCreditCost('1');
    setRemark('');
    setFormError('');
  };

  const openEdit = (m: ModelCatalogDto) => {
    setEditing(m);
    setCreating(true);
    setModelName(m.modelName);
    setCreditCost(String(m.creditCost));
    setRemark(m.remark ?? '');
    setFormError('');
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError('');
    setBusy(true);
    try {
      const body = {modelName: modelName.trim(), creditCost: Number(creditCost), remark: remark || null};
      if (editing) {
        await updateModelCatalog(editing.id, body);
      } else {
        await createModelCatalog(body);
      }
      setCreating(false);
      await reload();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '保存失败');
    } finally {
      setBusy(false);
    }
  };

  const remove = async (m: ModelCatalogDto) => {
    if (!window.confirm(`确定删除模型 ${m.modelName}？删除后该模型请求按默认 1 分/次计。`)) return;
    setPageError('');
    try {
      await deleteModelCatalog(m.id);
      await reload();
    } catch (err) {
      setPageError(err instanceof Error ? err.message : '删除失败');
    }
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h2>模型目录</h2>
          <p className="page-desc">
            平台级模型清单与积分比例：每次请求按所用模型的 creditCost 扣减积分（支持 1 位小数，
            多余位数舍弃不进位）；未登记的模型按 1 分/次计。Provider 的模型清单按名称引用此处模型。
          </p>
        </div>
        <div className="page-head-right">
          <button type="button" className="btn btn-primary" onClick={openCreate}>
            ＋ 登记模型
          </button>
        </div>
      </div>

      {pageError && <div className="form-error">{pageError}</div>}

      {rows === null ? (
        <div className="card">
          <p className="page-desc">加载中…</p>
        </div>
      ) : rows.length === 0 ? (
        <div className="card">
          <p className="page-desc">
            暂无登记模型：所有模型请求按默认 1 分/次计。点击右上角「登记模型」为高价模型设置更高比例。
          </p>
        </div>
      ) : (
        <div className="card">
          <table className="data-table">
            <thead>
              <tr>
                <th>模型名</th>
                <th>积分比例（分/次）</th>
                <th>备注</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((m) => (
                <tr key={m.id}>
                  <td style={{fontFamily: "ui-monospace, 'Cascadia Code', Consolas, monospace"}}>
                    {m.modelName}
                  </td>
                  <td>{m.creditCost}</td>
                  <td>
                    <div className="cell-ellipsis" title={m.remark ?? ''}>
                      {m.remark ?? '—'}
                    </div>
                  </td>
                  <td style={{whiteSpace: 'nowrap'}}>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => openEdit(m)}>
                      编辑
                    </button>{' '}
                    <button type="button" className="btn btn-danger btn-sm" onClick={() => void remove(m)}>
                      删除
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {creating && (
        <Modal
          title={editing ? `编辑模型：${editing.modelName}` : '登记模型'}
          subtitle="creditCost = 每次请求消耗的积分；支持 1 位小数，如 0.5"
          onClose={() => setCreating(false)}
        >
          <form onSubmit={(e) => void submit(e)} className="modal-form">
            <label>
              模型名<span className="field-hint">与请求 body.model 一致；全局唯一</span>
              <input
                type="text"
                value={modelName}
                onChange={(e) => setModelName(e.target.value)}
                placeholder="如 gpt-4o"
                required
              />
            </label>
            <label>
              积分比例<span className="field-hint">每次请求消耗积分；≥0.1，最多 1 位小数</span>
              <input
                type="number"
                min={0.1}
                max={1000000}
                step={0.1}
                value={creditCost}
                onChange={(e) => setCreditCost(e.target.value)}
                required
              />
            </label>
            <label>
              备注
              <input type="text" value={remark} onChange={(e) => setRemark(e.target.value)} placeholder="选填" />
            </label>
            {formError && <div className="form-error">{formError}</div>}
            <div className="modal-actions">
              <button type="button" className="btn btn-ghost" onClick={() => setCreating(false)}>
                取消
              </button>
              <button type="submit" className="btn btn-primary" disabled={busy}>
                {busy ? '提交中…' : '保存'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
