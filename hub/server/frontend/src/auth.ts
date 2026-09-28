// 会话存取：access/refresh token + 当前用户 + 组织列表 + 当前组织，存 localStorage。
// A0 控制台为内部工具，接受 localStorage 存 token 的 XSS 权衡（与主前端一致的做法）。
import type {OrgDto, UserDto} from './types';

const ACCESS_KEY = 'hub.accessToken';
const REFRESH_KEY = 'hub.refreshToken';
const USER_KEY = 'hub.user';
const ORGS_KEY = 'hub.orgs';
const CURRENT_ORG_KEY = 'hub.currentOrgId';

export interface Session {
  accessToken: string;
  refreshToken: string;
  user: UserDto;
  orgs: OrgDto[];
}

export function saveSession(s: Session): void {
  localStorage.setItem(ACCESS_KEY, s.accessToken);
  localStorage.setItem(REFRESH_KEY, s.refreshToken);
  localStorage.setItem(USER_KEY, JSON.stringify(s.user));
  localStorage.setItem(ORGS_KEY, JSON.stringify(s.orgs));
  // 首次登录默认选中第一个组织；无组织则清空旧选择
  if (s.orgs.length > 0) {
    if (!getCurrentOrgId()) setCurrentOrgId(s.orgs[0].id);
  } else {
    localStorage.removeItem(CURRENT_ORG_KEY);
  }
}

export function loadSession(): Session | null {
  const accessToken = localStorage.getItem(ACCESS_KEY);
  const refreshToken = localStorage.getItem(REFRESH_KEY);
  const userText = localStorage.getItem(USER_KEY);
  if (!accessToken || !refreshToken || !userText) return null;
  try {
    const user = JSON.parse(userText) as UserDto;
    const orgs = JSON.parse(localStorage.getItem(ORGS_KEY) || '[]') as OrgDto[];
    return {accessToken, refreshToken, user, orgs};
  } catch {
    return null;
  }
}

export function clearSession(): void {
  localStorage.removeItem(ACCESS_KEY);
  localStorage.removeItem(REFRESH_KEY);
  localStorage.removeItem(USER_KEY);
  localStorage.removeItem(ORGS_KEY);
  localStorage.removeItem(CURRENT_ORG_KEY);
}

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_KEY);
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_KEY);
}

export function getCurrentOrgId(): number | null {
  const v = localStorage.getItem(CURRENT_ORG_KEY);
  return v ? Number(v) : null;
}

export function setCurrentOrgId(orgId: number): void {
  localStorage.setItem(CURRENT_ORG_KEY, String(orgId));
}
