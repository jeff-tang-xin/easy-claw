/** 一段面包屑：text + 可选 onClick（可点击段）。 */
export interface Crumb {
  text: string;
  onClick?: () => void;
}

/**
 * 页面顶部面包屑。最末段为当前页（不可点、高亮）。
 * IA §0.2：组织维度页面形如「{组织} / {页面}」，平台维度形如「平台 / {页面}」。
 */
export default function Breadcrumb({items}: {items: Crumb[]}) {
  if (items.length === 0) return null;
  return (
    <nav className="top-breadcrumb" aria-label="面包屑">
      {items.map((c, i) => {
        const last = i === items.length - 1;
        return (
          <span key={i} className="crumb-seg">
            {i > 0 && <span className="crumb-sep">/</span>}
            {c.onClick && !last ? (
              <button type="button" className="crumb-link" onClick={c.onClick}>
                {c.text}
              </button>
            ) : (
              <span className={last ? 'crumb-current' : 'crumb-text'}>{c.text}</span>
            )}
          </span>
        );
      })}
    </nav>
  );
}
