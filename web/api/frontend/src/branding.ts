import {useSyncExternalStore} from 'react';
import {getJson} from './api';

/**
 * 界面品牌定制（侧边栏 logo / 浏览器标签页 / favicon）。
 * 配置源：后端 application.yml 的 easyclaw.branding.{name,subtitle,icon}，
 * 启动时经 GET /api/branding 拉取；拉取失败（老后端/未启动）静默回退默认值。
 */
export interface Branding {
  /** 应用名：侧边栏标题 + 浏览器标签页后缀 */
  name: string;
  /** 侧边栏标题下方的小字 */
  subtitle: string;
  /** 图标：emoji（默认 🦞）或图片地址（/xxx.png 或 http(s):// 外链），同时用作 favicon */
  icon: string;
}

const DEFAULT_BRANDING: Branding = {name: 'Easy-Claw', subtitle: 'AI 编程助手', icon: '🦞'};
/** index.html 的静态 title，用于判断标签页标题是否还没被聊天页接管 */
const DEFAULT_TITLE = `${DEFAULT_BRANDING.name} · ${DEFAULT_BRANDING.subtitle}`;

let current: Branding = DEFAULT_BRANDING;
const listeners = new Set<() => void>();

export function getBranding(): Branding {
  return current;
}

function subscribe(fn: () => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

/** React 组件订阅品牌配置（加载完成后自动重渲染） */
export function useBranding(): Branding {
  return useSyncExternalStore(subscribe, getBranding);
}

/** icon 是图片地址（站内路径或外链）而非 emoji */
export function isIconUrl(icon: string): boolean {
  return icon.startsWith('/') || /^https?:\/\//i.test(icon);
}

function applyFavicon(icon: string): void {
  let link = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
  if (!link) {
    link = document.createElement('link');
    link.rel = 'icon';
    document.head.appendChild(link);
  }
  if (isIconUrl(icon)) {
    link.removeAttribute('type');
    link.href = icon;
  } else {
    // emoji → 内联 SVG 文本 favicon
    link.type = 'image/svg+xml';
    link.href =
      'data:image/svg+xml,' +
      encodeURIComponent(
        `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><text y="0.9em" font-size="90">${icon}</text></svg>`,
      );
  }
}

/** 应用启动时调用一次；失败静默保留默认值（品牌属锦上添花，不阻塞 UI） */
export async function loadBranding(): Promise<void> {
  try {
    const b = await getJson<Partial<Branding>>('/api/branding');
    current = {
      name: b.name?.trim() || DEFAULT_BRANDING.name,
      subtitle: b.subtitle ?? DEFAULT_BRANDING.subtitle,
      icon: b.icon?.trim() || DEFAULT_BRANDING.icon,
    };
  } catch {
    return;
  }
  applyFavicon(current.icon);
  // 聊天页之外 title 仍是 index.html 的静态值时，同步成新品牌
  // （聊天页的「工作区名 · 应用名」由 ChatPage 的 effect 依赖 branding.name 自动修正）
  if (document.title === DEFAULT_TITLE) {
    document.title = `${current.name} · ${current.subtitle}`;
  }
  listeners.forEach((fn) => fn());
}
