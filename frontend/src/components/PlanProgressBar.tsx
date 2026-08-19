import {memo} from 'react';
import type {PlanState, PlanStep, PlanValidation} from '../chatStore';

/**
 * Plan 进度可视化组件
 * 将 GOAP 规划步骤渲染为醒目的步骤进度条，让用户一眼看到"在做第几步"
 */
function PlanProgressBarImpl({plan}: { plan: PlanState }) {
  const {steps, goal, validation} = plan;
  if (!steps || steps.length === 0) return null;

  const doneCount = steps.filter(s => s.status === 'done').length;
  const runningCount = steps.filter(s => s.status === 'running').length;
  const failedCount = steps.filter(s => s.status === 'failed').length;
  const total = steps.length;
  const progress = total > 0 ? (doneCount / total) * 100 : 0;

  return (
    <div className="plan-progress">
      <div className="plan-progress-header">
        <span className="plan-progress-goal" title={goal}>🎯 {goal}</span>
        <span className="plan-progress-count">
          {doneCount}/{total} 步
          {failedCount > 0 && <span className="plan-progress-failed"> · {failedCount} 失败</span>}
        </span>
      </div>

      <div className="plan-progress-bar">
        <div
          className="plan-progress-bar-fill"
          style={{width: `${progress}%`}}
        />
        {runningCount > 0 && (
          <div className="plan-progress-bar-pulse" />
        )}
      </div>

      {validation && !validation.valid && (
        <PlanValidationWarning validation={validation} />
      )}

      <div className="plan-progress-steps">
        {steps.map((step, i) => (
          <PlanStepItem
            key={i}
            step={step}
            index={i}
            isLast={i === steps.length - 1}
            isInvalid={validation ? validation.invalidSteps.includes(step.name) : false}
          />
        ))}
      </div>
    </div>
  );
}

function PlanValidationWarning({validation}: { validation: PlanValidation }) {
  return (
    <div className="plan-validation-warning">
      <div className="plan-validation-header">⚠️ Plan 校验失败</div>
      <div className="plan-validation-message">{validation.message}</div>
      {validation.availableActions.length > 0 && (
        <details className="plan-validation-actions">
          <summary>可用 Action ({validation.availableActions.length})</summary>
          <div className="plan-validation-action-list">
            {validation.availableActions.map((a, i) => (
              <code key={i} className="plan-validation-action">{a}</code>
            ))}
          </div>
        </details>
      )}
    </div>
  );
}

function PlanStepItem({step, index, isLast, isInvalid}: {
  step: PlanStep;
  index: number;
  isLast: boolean;
  isInvalid: boolean;
}) {
  const {status, name, description} = step;
  const icon = status === 'done' ? '✓' : status === 'running' ? '◉' : status === 'failed' ? '✕' : `${index + 1}`;
  const hasDesc = description && description.trim().length > 0;

  return (
    <div className={`plan-step ${status} ${isInvalid ? 'invalid' : ''}`}>
      <div className="plan-step-node">
        <span className="plan-step-icon">{isInvalid ? '⚠' : icon}</span>
        {!isLast && <div className="plan-step-connector" />}
      </div>
      <div className="plan-step-info">
        {hasDesc ? (
          <details className="plan-step-details">
            <summary className="plan-step-name-row">
              <span className="plan-step-name">{name}</span>
              {isInvalid && <span className="plan-step-invalid-tag">未注册</span>}
              <span className="plan-step-caret" />
            </summary>
            <div className="plan-step-description">{description}</div>
          </details>
        ) : (
          <div className="plan-step-name-row">
            <span className="plan-step-name">{name}</span>
            {isInvalid && <span className="plan-step-invalid-tag">未注册</span>}
          </div>
        )}
        {status === 'running' && (
          <div className="plan-step-status">
            <span className="spinner-tiny" /> 进行中
          </div>
        )}
        {status === 'failed' && (
          <div className="plan-step-status failed">失败</div>
        )}
      </div>
    </div>
  );
}

export const PlanProgressBar = memo(PlanProgressBarImpl);
