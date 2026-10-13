import {useMemo} from 'react';
import {marked} from 'marked';
import DOMPurify from 'dompurify';

/**
 * 轻量 Markdown 渲染，供「AI 产出的文本」统一使用：记录本条目、知识库正文等独立页面。
 *
 * marked 解析 + DOMPurify 消毒：这些内容全部来自模型输出，模型可能被提示注入诱导输出
 * 恶意脚本，直接 innerHTML 等于把注入升级成 XSS——消毒这一步不可省。
 * breaks: true 与 ChatPage 的 md() 保持一致：AI 写的换行就是想换行，不按标准 markdown
 * 的「单换行合并」处理。样式复用聊天消息的 .md-content 体系（styles.css），两处观感一致。
 */
export default function Markdown({text, className}: { text: string; className?: string }) {
  const html = useMemo(() => {
    const raw = marked.parse(text ?? '', {async: false, breaks: true}) as string;
    return DOMPurify.sanitize(raw);
  }, [text]);
  return (
    <div
      className={'md-content' + (className ? ' ' + className : '')}
      dangerouslySetInnerHTML={{__html: html}}
    />
  );
}
