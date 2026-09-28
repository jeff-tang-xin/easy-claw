// 运维文件管理面板：远程目录树（SFTP 懒加载）+ 右键菜单。
// 布局参照 Workbench 风格：左侧树、点开一层加载一层；右键菜单提供
// 刷新/新建文件/新建文件夹/上传/重命名/复制路径（文件另含下载、双击亦可下载）。
// 传输动作经 useOpsTransfers（进度在「文件任务中心」可见）。P1 无删除。
import {useCallback, useEffect, useRef, useState} from 'react';
import {getJson, postJson} from './api';

interface RemoteEntry {
  name: string;
  path: string;
  directory: boolean;
  size: number;
  modified: number;
}

interface TreeNode {
  entry: RemoteEntry;
  children?: TreeNode[];
  loading?: boolean;
  error?: string;
  expanded?: boolean;
}

interface MenuState {
  x: number;
  y: number;
  node: TreeNode;
}

interface Props {
  workspaceId: string;
  connId: number;
  upload: (workspaceId: string, connId: number, targetDir: string, file: File) => void;
  download: (workspaceId: string, connId: number, path: string, name: string) => Promise<void>;
  onClose: () => void;
}

/** 大文件阈值（50MB）：命中在名称后标橙色「大文件」 */
const LARGE_FILE = 50 * 1024 * 1024;

const joinPath = (parent: string, name: string) => ('/' === parent ? '/' + name : parent + '/' + name);
const parentOf = (path: string) => {
  if ('/' === path) return '/';
  const idx = path.lastIndexOf('/');
  return idx <= 0 ? '/' : path.substring(0, idx);
};

export default function OpsFilePanel({workspaceId, connId, upload, download, onClose}: Props) {
  // 虚拟根节点（path=/）；children 初始加载
  const [root, setRoot] = useState<TreeNode>({
    entry: {name: '/', path: '/', directory: true, size: 0, modified: 0},
    expanded: true,
  });
  const [menu, setMenu] = useState<MenuState | null>(null);
  const [selected, setSelected] = useState<string>('');
  const uploadInputRef = useRef<HTMLInputElement | null>(null);
  /** 上传目标目录（右键菜单选定；input onChange 时取用） */
  const uploadTargetRef = useRef('');

  /** 拉取某目录条目（不整树替换：按路径不可变更新目标节点） */
  const loadChildren = useCallback(async (node: TreeNode) => {
    const target = node.entry.path;
    patchNode(target, (n) => ({...n, loading: true, error: undefined}));
    try {
      const entries = await getJson<RemoteEntry[]>(
        `/api/ops/files?workspaceId=${encodeURIComponent(workspaceId)}&connId=${connId}`
        + `&path=${encodeURIComponent(target)}`);
      patchNode(target, (n) => ({
        ...n, loading: false, expanded: true,
        children: entries.map((e) => ({entry: e})),
      }));
    } catch (e) {
      patchNode(target, (n) => ({...n, loading: false, error: String(e)}));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId, connId]);

  /** 不可变树更新：按 path 定位节点（path 唯一） */
  const patchNode = (path: string, fn: (n: TreeNode) => TreeNode) => {
    setRoot((r) => {
      const walk = (n: TreeNode): TreeNode => {
        if (n.entry.path === path) return fn(n);
        if (!n.children) return n;
        return {...n, children: n.children.map(walk)};
      };
      return walk(r);
    });
  };

  // 初始加载根
  useEffect(() => {
    void loadChildren(root);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const toggle = (node: TreeNode) => {
    if (node.expanded) {
      patchNode(node.entry.path, (n) => ({...n, expanded: false}));
    } else if (node.children) {
      patchNode(node.entry.path, (n) => ({...n, expanded: true}));
    } else {
      void loadChildren(node);
    }
  };

  // ==================== 右键菜单动作 ====================

  const refreshNode = (node: TreeNode) => {
    if (node.entry.directory) void loadChildren(node);
    else void loadChildren({entry: {
      ...node.entry, path: parentOf(node.entry.path), directory: true,
    } as RemoteEntry});
  };

  const newFile = (node: TreeNode) => {
    const dir = node.entry.directory ? node.entry.path : parentOf(node.entry.path);
    const name = window.prompt('新建文件（文件名，创建在 ' + dir + '）');
    if (!name?.trim()) return;
    postJson('/api/ops/files/create', {workspaceId, connId, path: joinPath(dir, name.trim())})
      .then(() => void loadChildren({entry: {path: dir, directory: true} as RemoteEntry}))
      .catch((e) => window.alert('新建失败：' + String(e)));
  };

  const newFolder = (node: TreeNode) => {
    const dir = node.entry.directory ? node.entry.path : parentOf(node.entry.path);
    const name = window.prompt('新建文件夹（名称，创建在 ' + dir + '）');
    if (!name?.trim()) return;
    postJson('/api/ops/files/mkdir', {workspaceId, connId, path: joinPath(dir, name.trim())})
      .then(() => void loadChildren({entry: {path: dir, directory: true} as RemoteEntry}))
      .catch((e) => window.alert('新建失败：' + String(e)));
  };

  const renameNode = (node: TreeNode) => {
    const name = window.prompt('重命名为（仅名称，同目录）', node.entry.name);
    if (!name?.trim() || name.trim() === node.entry.name) return;
    const to = joinPath(parentOf(node.entry.path), name.trim());
    postJson('/api/ops/files/rename', {workspaceId, connId, from: node.entry.path, to})
      .then(() => void loadChildren({entry: {path: parentOf(node.entry.path), directory: true} as RemoteEntry}))
      .catch((e) => window.alert('重命名失败：' + String(e)));
  };

  const uploadHere = (node: TreeNode) => {
    uploadTargetRef.current = node.entry.directory ? node.entry.path : parentOf(node.entry.path);
    uploadInputRef.current?.click();
  };

  // ==================== 渲染 ====================

  const renderNode = (node: TreeNode, depth: number): React.ReactNode => {
    const e = node.entry;
    const isRoot = '/' === e.path;
    return (
      <div key={e.path}>
        <div
          className={'ops-fm-row' + (selected === e.path ? ' selected' : '')}
          style={{paddingLeft: 8 + depth * 14}}
          onClick={() => {
            setSelected(e.path);
            if (e.directory) toggle(node);
          }}
          onDoubleClick={() => {
            if (!e.directory) void download(workspaceId, connId, e.path, e.name);
          }}
          onContextMenu={(ev) => {
            ev.preventDefault();
            setSelected(e.path);
            setMenu({x: ev.clientX, y: ev.clientY, node});
          }}
        >
          {e.directory ? (
            <span className="ops-fm-arrow">{node.expanded ? '▾' : '▸'}</span>
          ) : (
            <span className="ops-fm-arrow" />
          )}
          <span className="ops-fm-icon">{e.directory ? '📁' : '📄'}</span>
          <span className="ops-fm-name" title={e.path}>
            {isRoot ? '根目录 /' : e.name}
          </span>
          {!e.directory && e.size >= LARGE_FILE && <span className="ops-fm-large">大文件</span>}
        </div>
        {node.loading && <div className="ops-fm-hint" style={{paddingLeft: 8 + (depth + 1) * 14}}>加载中…</div>}
        {node.error && <div className="ops-fm-error" style={{paddingLeft: 8 + (depth + 1) * 14}}>{node.error}</div>}
        {e.directory && node.expanded && node.children?.map((c) => renderNode(c, depth + 1))}
      </div>
    );
  };

  return (
    <div className="ops-fm-panel">
      <div className="ops-fm-head">
        <span>📂 文件管理</span>
        <button className="ops-fm-close" onClick={onClose} title="收起">×</button>
      </div>
      <div className="ops-fm-tree" onContextMenu={(e) => e.preventDefault()}>
        {renderNode(root, 0)}
      </div>

      {/* 上传文件选择器 */}
      <input
        ref={uploadInputRef}
        type="file"
        hidden
        onChange={(ev) => {
          const f = ev.target.files?.[0];
          if (f) upload(workspaceId, connId, uploadTargetRef.current, f);
          ev.target.value = '';
        }}
      />

      {/* 右键菜单 */}
      {menu && (
        <div
          className="ops-fm-menu"
          style={{left: menu.x, top: menu.y}}
          onClick={() => setMenu(null)}
        >
          <div onClick={() => refreshNode(menu.node)}>刷新</div>
          <div onClick={() => newFile(menu.node)}>新建文件</div>
          <div onClick={() => newFolder(menu.node)}>新建文件夹</div>
          <div onClick={() => uploadHere(menu.node)}>上传</div>
          {!menu.node.entry.directory && (
            <div onClick={() => void download(workspaceId, connId, menu.node.entry.path, menu.node.entry.name)}>
              下载
            </div>
          )}
          <div onClick={() => renameNode(menu.node)}>重命名</div>
          <div onClick={() => void navigator.clipboard.writeText(menu.node.entry.path)}>复制路径</div>
        </div>
      )}
    </div>
  );
}
