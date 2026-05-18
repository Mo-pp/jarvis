import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react';
import {
  ArrowLeft,
  ArrowUp,
  Bot,
  Check,
  ChevronDown,
  ChevronUp,
  Circle,
  Clock,
  CornerDownRight,
  Database,
  Ellipsis,
  FileText,
  GitBranch,
  Globe,
  ListChecks,
  LoaderCircle,
  LogIn,
  Menu,
  MessageSquare,
  PanelLeftClose,
  PanelRightClose,
  PanelRightOpen,
  Paperclip,
  Pencil,
  Pin,
  PinOff,
  Plus,
  Search,
  Settings,
  Sparkles,
  RefreshCw,
  Upload,
  Trash2,
  UserCircle,
  X,
} from 'lucide-react';
import { ApiError, authService, chatService, fileService, getStoredAuth, resourceService, skillService } from '../services/api';
import { useChatStream } from '../hooks/useChatStream';
import type {
  ArtifactEnvelope,
  ArtifactReadyPayload,
  AssistantCheckpointPayload,
  AssistantActionItem,
  AuthUser,
  ChatMessage,
  DelegationActionPayload,
  ExistingSkillItem,
  FileUploadResponse,
  MindmapData,
  OptimizeResult,
  Question,
  QuestionOption,
  QuestionnaireArtifact,
  ResourceDetailResponse,
  ResourceItemResponse,
  ResumeVO,
  RunStepKind,
  RunStepPayload,
  RunStepStatus,
  SessionStartedPayload,
  Session,
  SkillUploadResponse,
  TaskItem,
  TaskProgress,
  ToolUseActionPayload,
  UserAnswer,
} from '../types';
import type { ResumeOptimizeRequest } from './ResumeOptimizePanel';
import { createPortal } from 'react-dom';

const MindmapViewer = lazy(async () => {
  const module = await import('./MindmapViewer');
  return { default: module.MindmapViewer };
});

const OptimizeResultCard = lazy(async () => {
  const module = await import('./OptimizeResultCard');
  return { default: module.OptimizeResultCard };
});

const QuestionDialog = lazy(async () => {
  const module = await import('./QuestionDialog');
  return { default: module.QuestionDialog };
});

const ResumeWorkbench = lazy(async () => {
  const module = await import('./ResumeWorkbench');
  return { default: module.ResumeWorkbench };
});

const SettingsPage = lazy(async () => {
  const module = await import('./SettingsPage');
  return { default: module.SettingsPage };
});

type SessionMenuPosition = {
  sessionId: string;
  top: number;
  left: number;
};

type SkillsPanelTab = 'existing' | 'add';
type RunStepNode = RunStepPayload & {
  children: RunStepNode[];
  firstSeenAt: number;
  lastEventAt: number;
};
type SubAgentSummary = {
  id: string;
  label: string;
  status: RunStepStatus;
};
type AssistantActionGroup = {
  id: string;
  kind: 'tool_group';
  title: string;
  summary: string;
  status: RunStepStatus;
  items: Extract<AssistantActionItem, { kind: 'tool_use' }>[];
};
type AssistantTimelineAction = Exclude<AssistantActionItem, { kind: 'delegation' }>;
type AssistantDelegationGroup = {
  id: string;
  kind: 'delegation_group';
  agentId: string;
  agentLabel: string;
  agentType?: string;
  title: string;
  task?: string;
  status: RunStepStatus;
  summary?: string;
  error?: string;
  turnCount?: number;
  maxTurns?: number;
  inputTokens?: number;
  outputTokens?: number;
  children: AssistantTimelineAction[];
};
type AssistantTimelineItem = AssistantTimelineAction | AssistantActionGroup | AssistantDelegationGroup;

const DEFAULT_WALLPAPER_URL = 'https://images.unsplash.com/photo-1517685352821-92cf88aee5a5?w=1920&q=80';
const WALLPAPER_STORAGE_KEY = 'jarvis.wallpaper';

const KNOWLEDGE_BASE_ACCEPT = [
  '.pdf',
  '.md',
  '.markdown',
  '.html',
  '.docx',
  '.txt',
  '.epub',
  '.xlsx',
  '.xls',
  '.xlsm',
  '.pptx',
  '.py',
  '.js',
  '.jsx',
  '.ts',
  '.tsx',
  '.java',
  '.go',
  '.rs',
  '.c',
  '.cc',
  '.cpp',
  '.h',
  '.hpp',
  '.cs',
  '.php',
  '.rb',
  '.swift',
  '.kt',
  '.kts',
  '.scala',
  '.sh',
  '.sql',
  '.json',
  '.yaml',
  '.yml',
  '.toml',
  '.xml',
  '.css',
  '.scss',
  '.less',
  '.vue',
  '.svelte',
  '.ipynb',
].join(',');

const ROOT_RESOURCE_PATH = 'viking://resources/';
const ROOT_WORKSPACE_PATH = 'viking://';
const ROOT_WORKSPACE_PATH_WITHOUT_SLASH = 'viking:';

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function normalizeResourceDirectoryPath(path: string, rootPath = ROOT_RESOURCE_PATH): string {
  const trimmed = path.trim().replace(/\\/g, '/');
  const normalizedRootPath = rootPath.endsWith('/') ? rootPath : `${rootPath}/`;
  const rootPathWithoutSlash = normalizedRootPath.slice(0, -1);
  if (!trimmed || trimmed === rootPathWithoutSlash) return normalizedRootPath;
  if (normalizedRootPath === ROOT_WORKSPACE_PATH && trimmed === ROOT_WORKSPACE_PATH_WITHOUT_SLASH) {
    return ROOT_WORKSPACE_PATH;
  }

  let normalized = trimmed;
  if (!normalized.startsWith('viking://')) {
    normalized = `${normalizedRootPath}${normalized.replace(/^\/+/, '')}`;
  }

  return normalized.endsWith('/') ? normalized : `${normalized}/`;
}

function getResourceParentPath(path: string, rootPath = ROOT_RESOURCE_PATH): string {
  const normalizedRootPath = rootPath.endsWith('/') ? rootPath : `${rootPath}/`;
  const normalizedPath = normalizeResourceDirectoryPath(path, normalizedRootPath);
  if (normalizedPath === normalizedRootPath) return normalizedRootPath;

  const withoutTrailingSlash = normalizedPath.slice(0, -1);
  const lastSlashIndex = withoutTrailingSlash.lastIndexOf('/');
  const parentPath = lastSlashIndex >= 0 ? withoutTrailingSlash.slice(0, lastSlashIndex + 1) : normalizedRootPath;
  return parentPath.startsWith(normalizedRootPath) ? parentPath : normalizedRootPath;
}

function getResourcePackageRootUri(uri?: string | null): string | null {
  const normalizedUri = uri?.trim().replace(/\\/g, '/');
  if (!normalizedUri || !normalizedUri.startsWith(ROOT_RESOURCE_PATH)) return null;

  const relativePath = normalizedUri.slice(ROOT_RESOURCE_PATH.length).replace(/^\/+/, '');
  const segments = relativePath.split('/').filter(Boolean);
  const rootName = segments[0];
  if (!rootName) return null;

  if (segments.length === 1 && !normalizedUri.endsWith('/') && rootName.includes('.')) {
    return `${ROOT_RESOURCE_PATH}${rootName}`;
  }

  return `${ROOT_RESOURCE_PATH}${rootName}/`;
}

function normalizeQuotedResourceUris(uris: string[]): string[] {
  const seen = new Set<string>();
  const normalizedUris: string[] = [];

  uris.forEach((uri) => {
    const normalizedUri = uri.trim();
    if (!normalizedUri || seen.has(normalizedUri)) return;
    seen.add(normalizedUri);
    normalizedUris.push(normalizedUri);
  });

  return normalizedUris;
}

function collectStringValues(value: unknown, output: string[] = []): string[] {
  if (typeof value === 'string') {
    output.push(value);
    return output;
  }
  if (Array.isArray(value)) {
    value.forEach((item) => collectStringValues(item, output));
    return output;
  }
  if (isRecord(value)) {
    Object.values(value).forEach((item) => collectStringValues(item, output));
  }
  return output;
}

function extractVikingUrisFromText(text: string): string[] {
  const matches = text.match(/viking:\/\/[^\s"'<>),，。；;]+/g);
  return matches?.map((uri) => uri.replace(/[)\].,，。；;]+$/, '')) || [];
}

function getActionResourceUris(action: Pick<ToolUseActionPayload, 'resourceUris' | 'preview' | 'description' | 'summary'>): string[] {
  const directUris = Array.isArray(action.resourceUris) ? action.resourceUris : [];
  const previewUris = collectStringValues(action.preview)
    .flatMap((value) => extractVikingUrisFromText(value));
  const textUris = [action.description, action.summary]
    .filter((value): value is string => Boolean(value))
    .flatMap(extractVikingUrisFromText);

  return normalizeQuotedResourceUris([...directUris, ...previewUris, ...textUris]);
}

function buildReferencedUserMessage(content: string, uris: string[]): string {
  const normalizedUris = normalizeQuotedResourceUris(uris);
  const trimmedContent = content.trim();
  if (normalizedUris.length === 0) return trimmedContent;

  const referenceBlock = [
    '[参考路径提示]',
    '用户已经明确引用了以下知识库路径。请优先关注这些路径，并把它们视为当前问题的重要上下文；如果需要查看文件、目录、代码或文档，请优先从这些路径开始解读：',
    ...normalizedUris.map((uri) => `- ${uri}`),
    '[/参考路径提示]',
  ].join('\n');

  return trimmedContent ? `${referenceBlock}\n\n${trimmedContent}` : referenceBlock;
}

function buildReferencedDisplaySummary(uris: string[]): string {
  const normalizedUris = normalizeQuotedResourceUris(uris);
  if (normalizedUris.length === 0) return '';
  if (normalizedUris.length === 1) return '已发送 1 个引用资源';
  return `已发送 ${normalizedUris.length} 个引用资源`;
}

function buildReferencedDisplayContent(content: string, uris: string[]): string {
  const normalizedUris = normalizeQuotedResourceUris(uris);
  const trimmedContent = content.trim();
  if (trimmedContent) return trimmedContent;
  if (normalizedUris.length === 0) return trimmedContent;
  return buildReferencedDisplaySummary(normalizedUris);
}

function isResourceDirectoryLike(item?: ResourceItemResponse | null): boolean {
  if (!item?.uri) return false;
  if (item.directory === true) return true;
  if (item.directory === false) return false;
  const type = item.type?.toLowerCase() || '';
  if (type.includes('dir') || type.includes('folder')) return true;
  if (item.uri.endsWith('/')) return true;

  const normalizedUri = item.uri.endsWith('/') ? item.uri.slice(0, -1) : item.uri;
  const lastSlashIndex = normalizedUri.lastIndexOf('/');
  const leafName = lastSlashIndex >= 0 ? normalizedUri.slice(lastSlashIndex + 1) : normalizedUri;
  return Boolean(leafName) && !leafName.includes('.');
}

function cleanMarkdown(md: string): string {
  let cleaned = md.trim();
  if (cleaned.startsWith('```')) {
    cleaned = cleaned.split('\n').slice(1).join('\n');
  }
  if (cleaned.endsWith('```')) {
    cleaned = cleaned.split('\n').slice(0, -1).join('\n');
  }
  return cleaned.trim();
}

function formatSkillUpdatedAt(updatedAt?: string | null): string {
  if (!updatedAt) return '暂无时间';

  const date = new Date(updatedAt);
  if (Number.isNaN(date.getTime())) return updatedAt;

  return date.toLocaleString('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function formatResourceSize(size: number): string {
  if (size < 1024) return `${size} B`;
  return `${(size / 1024).toFixed(size >= 10 * 1024 ? 0 : 1)} KB`;
}

function createMindmapFromMarkdown(markdown: unknown): MindmapData | null {
  if (typeof markdown !== 'string') return null;

  const cleaned = cleanMarkdown(markdown);
  if (!cleaned) return null;

  return { type: 'mindmap', markdown: cleaned };
}

function normalizeMindmapPayload(payload: unknown): MindmapData | null {
  if (!isRecord(payload)) return null;

  const type = typeof payload.type === 'string' ? payload.type.toLowerCase() : '';
  if (type !== 'mindmap') return null;

  return createMindmapFromMarkdown(payload.markdown);
}

type StructuredArtifacts = {
  resume?: ResumeVO;
  optimizeResult?: OptimizeResult;
  questionnaire?: QuestionnaireArtifact;
};

function asStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((item) => (typeof item === 'string' ? item.trim() : ''))
    .filter(Boolean);
}

function asRecordArray<T>(value: unknown): T[] {
  if (!Array.isArray(value)) return [];
  return value.filter(isRecord) as T[];
}

function normalizeResumePayload(payload: unknown): ResumeVO | null {
  if (!isRecord(payload)) return null;

  const candidate = isRecord(payload.resume) ? payload.resume : payload;
  const hasResumeShape =
    isRecord(candidate.basicInfo) ||
    isRecord(candidate.jobIntention) ||
    typeof candidate.summary === 'string' ||
    Array.isArray(candidate.educationList) ||
    Array.isArray(candidate.workList) ||
    Array.isArray(candidate.projectList) ||
    Array.isArray(candidate.skillList);

  if (!hasResumeShape) return null;

  return {
    basicInfo: isRecord(candidate.basicInfo) ? candidate.basicInfo : {},
    jobIntention: isRecord(candidate.jobIntention) ? candidate.jobIntention : {},
    summary: typeof candidate.summary === 'string' ? candidate.summary : '',
    educationList: asRecordArray(candidate.educationList),
    workList: asRecordArray(candidate.workList),
    projectList: asRecordArray(candidate.projectList),
    campusList: asRecordArray(candidate.campusList),
    awardList: asRecordArray(candidate.awardList),
    skillList: asRecordArray(candidate.skillList),
  };
}

function normalizeOptimizeResultPayload(payload: unknown): OptimizeResult | null {
  if (!isRecord(payload)) return null;

  const type = typeof payload.type === 'string' ? payload.type.toLowerCase() : '';
  const hasOptimizeShape =
    type === 'optimize_result' ||
    typeof payload.matchScore === 'number' ||
    typeof payload.matchScore === 'string' ||
    isRecord(payload.matchAnalysis) ||
    Array.isArray(payload.suggestions) ||
    Array.isArray(payload.highlights);

  if (!hasOptimizeShape) return null;

  const rawScore = typeof payload.matchScore === 'number'
    ? payload.matchScore
    : typeof payload.matchScore === 'string'
      ? Number.parseFloat(payload.matchScore)
      : undefined;
  const matchAnalysis = isRecord(payload.matchAnalysis) ? payload.matchAnalysis : {};
  const optimizedResume = normalizeResumePayload(payload.optimizedResume);
  const resume = normalizeResumePayload(payload.resume);

  return {
    type: 'optimize_result',
    matchScore: Number.isFinite(rawScore) ? rawScore : undefined,
    matchAnalysis: {
      matchedSkills: asStringArray(matchAnalysis.matchedSkills),
      missingSkills: asStringArray(matchAnalysis.missingSkills),
      matchedBonus: asStringArray(matchAnalysis.matchedBonus),
      experienceMatch: typeof matchAnalysis.experienceMatch === 'string' ? matchAnalysis.experienceMatch : undefined,
      educationMatch: typeof matchAnalysis.educationMatch === 'string' ? matchAnalysis.educationMatch : undefined,
    },
    suggestions: asStringArray(payload.suggestions),
    highlights: asStringArray(payload.highlights),
    optimizedResume: optimizedResume || undefined,
    resume: resume || undefined,
  };
}

function normalizeQuestionnairePayload(payload: unknown): QuestionnaireArtifact | null {
  if (!isRecord(payload)) return null;

  const type = typeof payload.type === 'string' ? payload.type.toLowerCase() : '';
  if (type !== 'questionnaire' || !Array.isArray(payload.questions) || payload.questions.length === 0) {
    return null;
  }

  const questions = payload.questions
    .filter(isRecord)
    .map((item, index): Question => ({
      questionId: typeof item.questionId === 'string' && item.questionId
        ? item.questionId
        : `question_${index + 1}`,
      questionText: typeof item.questionText === 'string' && item.questionText
        ? item.questionText
        : typeof item.label === 'string' && item.label
          ? item.label
          : typeof item.title === 'string' && item.title
            ? item.title
            : `问题 ${index + 1}`,
      questionType: (typeof item.questionType === 'string' && item.questionType
        ? item.questionType
        : typeof item.type === 'string' && item.type
          ? item.type
          : 'text') as Question['questionType'],
      options: Array.isArray(item.options)
        ? (item.options.filter(isRecord) as unknown as QuestionOption[])
        : undefined,
      allowCustomInput: typeof item.allowCustomInput === 'boolean' ? item.allowCustomInput : undefined,
      customInputPlaceholder: typeof item.customInputPlaceholder === 'string'
        ? item.customInputPlaceholder
        : typeof item.placeholder === 'string'
          ? item.placeholder
          : undefined,
      required: typeof item.required === 'boolean' ? item.required : undefined,
      defaultValue: typeof item.defaultValue === 'string' ? item.defaultValue : undefined,
    }));

  if (questions.length === 0) return null;

  return {
    type: 'questionnaire',
    questionnaireId: typeof payload.questionnaireId === 'string' ? payload.questionnaireId : undefined,
    title: typeof payload.title === 'string' ? payload.title : undefined,
    sourceTool: typeof payload.sourceTool === 'string' ? payload.sourceTool : undefined,
    questions,
  };
}

function normalizeStructuredPayload(payload: unknown, requireExplicitType = false): StructuredArtifacts | null {
  if (!isRecord(payload)) return null;

  const type = typeof payload.type === 'string' ? payload.type.toLowerCase() : '';
  const artifacts: StructuredArtifacts = {};

  if (type === 'resume') {
    const resume = normalizeResumePayload(payload.resume || payload);
    if (resume) artifacts.resume = resume;
  } else if (type === 'optimize_result') {
    const optimizeResult = normalizeOptimizeResultPayload(payload);
    if (optimizeResult) {
      artifacts.optimizeResult = optimizeResult;
      artifacts.resume = optimizeResult.optimizedResume || optimizeResult.resume || undefined;
    }
  } else if (type === 'questionnaire') {
    const questionnaire = normalizeQuestionnairePayload(payload);
    if (questionnaire) artifacts.questionnaire = questionnaire;
  } else if (!requireExplicitType && !type) {
    const resume = normalizeResumePayload(payload);
    if (resume) artifacts.resume = resume;
    const optimizeResult = normalizeOptimizeResultPayload(payload);
    if (optimizeResult) artifacts.optimizeResult = optimizeResult;
  }

  return artifacts.resume || artifacts.optimizeResult || artifacts.questionnaire ? artifacts : null;
}

function mergeArtifacts(current: StructuredArtifacts | null, next: StructuredArtifacts | null): StructuredArtifacts | null {
  if (!current) return next;
  if (!next) return current;
  return {
    resume: next.resume || current.resume,
    optimizeResult: next.optimizeResult || current.optimizeResult,
    questionnaire: next.questionnaire || current.questionnaire,
  };
}

function collectArtifactJsonCandidates(text: string): Set<string> {
  const trimmed = text.trim();
  const candidates = new Set<string>();
  if (!trimmed) return candidates;

  candidates.add(trimmed);
  const singleFenceMatch = text.trim().match(/^```(?:\w+)?\s*\n([\s\S]*?)\n?```$/i);
  if (singleFenceMatch?.[1]) {
    candidates.add(singleFenceMatch[1].trim());
  }

  const fencedJsonRegex = /```(?:\w+)?\s*\n([\s\S]*?)```/gi;
  for (const match of text.matchAll(fencedJsonRegex)) {
    if (match[1]) candidates.add(match[1].trim());
  }

  return candidates;
}

function parseMindmapJsonText(text: string): MindmapData | null {
  const candidates = collectArtifactJsonCandidates(text);

  for (const candidate of candidates) {
    if (!candidate) continue;
    try {
      const parsed = JSON.parse(candidate);
      const mindmap = normalizeMindmapPayload(parsed);
      if (mindmap) return mindmap;
    } catch {
      // Continue with other possible JSON candidates.
    }
  }

  return null;
}

function parseStructuredArtifactsText(text: string): StructuredArtifacts | null {
  const candidates = collectArtifactJsonCandidates(text);
  let artifacts: StructuredArtifacts | null = null;

  for (const candidate of candidates) {
    if (!candidate) continue;
    try {
      const parsed = JSON.parse(candidate);
      artifacts = mergeArtifacts(artifacts, normalizeStructuredPayload(parsed, true));
    } catch {
      // Continue with other possible JSON candidates.
    }
  }

  return artifacts;
}

function parseStructuredArtifactsData(data: unknown): StructuredArtifacts | null {
  const direct = normalizeStructuredPayload(data);
  if (direct) return direct;
  return typeof data === 'string' ? parseStructuredArtifactsText(data) : null;
}

function normalizeArtifactEnvelope(envelope: ArtifactEnvelope | Record<string, unknown>): StructuredArtifacts | null {
  if (!isRecord(envelope)) return null;

  const type = typeof envelope.type === 'string' ? envelope.type.toLowerCase() : '';
  const payload = isRecord(envelope.payload)
    ? { ...envelope.payload, type: typeof envelope.payload.type === 'string' ? envelope.payload.type : type }
    : envelope.payload;

  return normalizeStructuredPayload(payload, true);
}

function parseArtifactEnvelopes(data: unknown): StructuredArtifacts | null {
  if (!Array.isArray(data)) return null;

  return data.reduce<StructuredArtifacts | null>((current, item) => {
    if (!isRecord(item)) return current;
    return mergeArtifacts(current, normalizeArtifactEnvelope(item));
  }, null);
}

function parseMindmapArtifactEnvelopes(data: unknown): MindmapData | null {
  if (!Array.isArray(data)) return null;

  for (const item of data) {
    if (!isRecord(item)) continue;
    const type = typeof item.type === 'string' ? item.type.toLowerCase() : '';
    if (type !== 'mindmap') continue;

    const payload = isRecord(item.payload)
      ? { ...item.payload, type: typeof item.payload.type === 'string' ? item.payload.type : type }
      : item.payload;
    const mindmap = normalizeMindmapPayload(payload);
    if (mindmap) return mindmap;
  }

  return null;
}

function parseMindmapData(mindmapData: unknown): MindmapData | null {
  const directMindmap = normalizeMindmapPayload(mindmapData);
  if (directMindmap) return directMindmap;
  if (!mindmapData || typeof mindmapData !== 'string') return null;

  return parseMindmapJsonText(mindmapData);
}

function parseQuestionnaireData(questionnaireData: unknown): QuestionnaireArtifact | null {
  const directQuestionnaire = normalizeQuestionnairePayload(questionnaireData);
  if (directQuestionnaire) return directQuestionnaire;
  if (!questionnaireData || typeof questionnaireData !== 'string') return null;

  const candidates = collectArtifactJsonCandidates(questionnaireData);
  for (const candidate of candidates) {
    if (!candidate) continue;
    try {
      const parsed = JSON.parse(candidate);
      const questionnaire = normalizeQuestionnairePayload(parsed);
      if (questionnaire) return questionnaire;
    } catch {
      // Continue with other candidates.
    }
  }

  return null;
}

function isMindmapOnlyContent(content: string, mindmap: MindmapData | null): boolean {
  if (!mindmap) return false;

  const trimmed = content.trim();
  if (!trimmed) return true;

  return Boolean(parseMindmapJsonText(trimmed));
}

function getStructuredDisplayContent(artifacts: StructuredArtifacts | null): string | null {
  if (!artifacts?.resume && !artifacts?.optimizeResult && !artifacts?.questionnaire) return null;
  if (artifacts.questionnaire) return 'Jarvis 需要你补充一些信息，请点击作答。';
  if (artifacts.optimizeResult) return '已生成简历优化分析，可在工作台查看。';
  if (artifacts.resume) return '已生成简历，可在工作台打开预览和编辑。';
  return null;
}

function getArtifactAwareDisplayContent(
  content: string,
  artifacts: StructuredArtifacts | null,
  mindmap?: MindmapData | null,
): string {
  const trimmed = content.trim();
  const structuredFallback = getStructuredDisplayContent(artifacts);
  const fallback = structuredFallback || (mindmap ? '已生成思维导图，可在工作台打开查看。' : '');

  if (!trimmed) return fallback;
  if (artifacts && parseStructuredArtifactsText(trimmed)) return fallback;
  if (mindmap && isMindmapOnlyContent(trimmed, mindmap)) return '已生成思维导图，可在工作台打开查看。';

  return content;
}

function getStreamingStructuredDisplayContent(content: string): string | null {
  const normalized = content.toLowerCase();
  if (normalized.includes('"type"') && normalized.includes('"optimize_result"')) {
    return '正在生成简历优化分析...';
  }
  if (normalized.includes('"type"') && normalized.includes('"questionnaire"')) {
    return '正在生成问卷...';
  }
  if (normalized.includes('"type"') && normalized.includes('"resume"')) {
    return '正在生成简历...';
  }

  return null;
}

function getMessageMindmap(message: ChatMessage): MindmapData | null {
  if (message.role !== 'assistant') return null;
  return message.mindmap || null;
}

function getMessageArtifacts(message: ChatMessage): StructuredArtifacts | null {
  if (message.role !== 'assistant') return null;

  return {
    resume: message.resumeData,
    optimizeResult: message.optimizeResult,
    questionnaire: message.questionnaire,
  };
}

function formatTimeAgo(dateString: string): string {
  if (!dateString) return '';
  const date = new Date(dateString);
  if (Number.isNaN(date.getTime())) return '';

  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMs / 3600000);
  const diffDays = Math.floor(diffMs / 86400000);

  if (diffMins < 1) return '鍒氬垰';
  if (diffMins < 60) return `${diffMins}m`;
  if (diffHours < 24) return `${diffHours}h`;
  if (diffDays < 7) return `${diffDays}d`;
  return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
}
function normalizeSession(item: unknown): Session {
  const now = new Date().toISOString();

  if (typeof item === 'string') {
    return {
      sessionId: item,
      title: '新对话',
      createdAt: now,
      lastActive: now,
      pinned: false,
    };
  }

  const session = item as Session;
  return {
    ...session,
    title: session.title?.trim() || '新对话',
    createdAt: session.createdAt || now,
    lastActive: session.lastActive || session.lastActiveAt || now,
    pinned: Boolean(session.pinned),
  };
}

function normalizeSessionList(raw: unknown): Session[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((item) => normalizeSession(item));
}

function getSessionTitle(session: Session): string {
  return session.title?.trim() || '新对话';
}

function normalizeTaskPlan(raw: unknown): TaskItem[] {
  if (!Array.isArray(raw)) return [];

  return raw
    .filter((task): task is Record<string, unknown> => Boolean(task) && typeof task === 'object')
    .map((task, index) => ({
      taskId: String(task.taskId || `task-${index + 1}`),
      description: String(task.description || '未命名任务'),
      detail: task.detail != null ? String(task.detail) : '',
      status: String(task.status || 'pending'),
      createdAt: typeof task.createdAt === 'number' ? task.createdAt : undefined,
      updatedAt: typeof task.updatedAt === 'number' ? task.updatedAt : undefined,
    }));
}

function buildTaskProgress(tasks: TaskItem[]): TaskProgress {
  return tasks.reduce<TaskProgress>(
    (progress, task) => {
      progress.total += 1;
      if (task.status === 'completed') progress.completed += 1;
      else if (task.status === 'in_progress') progress.in_progress += 1;
      else if (task.status === 'skipped') progress.skipped += 1;
      else progress.pending += 1;
      return progress;
    },
    { total: 0, pending: 0, in_progress: 0, completed: 0, skipped: 0 },
  );
}

function getTaskProgress(taskPlan: TaskItem[], progress?: TaskProgress): TaskProgress {
  return progress || buildTaskProgress(taskPlan);
}

function getTaskStatusLabel(status: string): string {
  if (status === 'completed') return '已完成';
  if (status === 'in_progress') return '进行中';
  if (status === 'skipped') return '已跳过';
  return '待处理';
}

function getRunStepStatusLabel(status: RunStepStatus): string {
  if (status === 'success') return '完成';
  if (status === 'failed') return '失败';
  if (status === 'blocked') return '阻塞';
  if (status === 'pending') return '等待';
  return '运行中';
}

function getRunStepKindLabel(kind: RunStepKind): string {
  if (kind === 'llm') return 'LLM';
  if (kind === 'tool_batch') return '工具组';
  if (kind === 'sub_agent') return '子代理';
  return '工具';
}

function getRunStepBrief(payload: RunStepPayload): string {
  const name = payload.title || payload.name;
  const normalizedName = name.toLowerCase();
  const statusLabel = getRunStepStatusLabel(payload.status);

  if (payload.kind === 'tool_batch') {
    return payload.status === 'success' ? '批量工具调用完成' : '批量工具调用中';
  }

  if (payload.kind === 'sub_agent') {
    return payload.status === 'success'
      ? `${payload.agentLabel || name} 已完成`
      : `${payload.agentLabel || name} 处理中`;
  }

  if (normalizedName.includes('edit') || normalizedName.includes('write')) {
    return payload.status === 'success' ? `编辑完成：${name}` : `正在编辑：${name}`;
  }

  if (
    normalizedName.includes('shell') ||
    normalizedName.includes('command') ||
    normalizedName.includes('powershell') ||
    normalizedName.includes('bash')
  ) {
    return payload.status === 'success' ? `命令运行成功：${name}` : `命令运行中：${name}`;
  }

  return payload.status === 'success'
    ? `使用工具完成：${name}`
    : `使用工具：${name}（${statusLabel}）`;
}

function getToolActionStatus(actions: Extract<AssistantActionItem, { kind: 'tool_use' }>[]): RunStepStatus {
  if (actions.some((action) => action.status === 'failed')) return 'failed';
  if (actions.some((action) => action.status === 'running')) return 'running';
  if (actions.some((action) => action.status === 'pending')) return 'pending';
  if (actions.some((action) => action.status === 'blocked')) return 'blocked';
  return 'success';
}

function createDelegationGroup(action: Extract<AssistantActionItem, { kind: 'delegation' }>, children: AssistantTimelineAction[] = []): AssistantDelegationGroup {
  return {
    id: action.id,
    kind: 'delegation_group',
    agentId: action.agentId,
    agentLabel: action.agentLabel,
    agentType: action.agentType,
    title: action.title || `委托给 ${action.agentLabel || '子 Agent'}`,
    task: action.task,
    status: action.status,
    summary: action.summary,
    error: action.error,
    turnCount: action.turnCount,
    maxTurns: action.maxTurns,
    inputTokens: action.inputTokens,
    outputTokens: action.outputTokens,
    children,
  };
}

function buildAssistantTimeline(actions?: AssistantActionItem[]): AssistantTimelineItem[] {
  if (!actions?.length) return [];

  const timeline: AssistantTimelineItem[] = [];
  const groupIndex = new Map<string, number>();
  const delegationIndexByAgentId = new Map<string, number>();

  const appendTopLevelAction = (action: AssistantTimelineAction) => {
    if (action.kind !== 'tool_use' || !action.groupId) {
      timeline.push(action);
      return;
    }

    const existingIndex = groupIndex.get(action.groupId);
    if (existingIndex == null) {
      const group: AssistantActionGroup = {
        id: action.groupId,
        kind: 'tool_group',
        title: action.groupTitle || action.title || action.toolName,
        summary: action.groupSummary || action.summary || action.description || '',
        status: getToolActionStatus([action]),
        items: [action],
      };
      groupIndex.set(action.groupId, timeline.length);
      timeline.push(group);
      return;
    }

    const existing = timeline[existingIndex];
    if (existing.kind !== 'tool_group') return;
    const nextItems = existing.items.some((item) => item.id === action.id)
      ? existing.items.map((item) => (item.id === action.id ? action : item))
      : [...existing.items, action];
    timeline[existingIndex] = {
      ...existing,
      status: getToolActionStatus(nextItems),
      summary: action.groupSummary || action.summary || existing.summary,
      items: nextItems,
    };
  };

  const appendDelegationChild = (delegationIndex: number, action: AssistantTimelineAction) => {
    const delegation = timeline[delegationIndex];
    if (delegation?.kind !== 'delegation_group') return false;
    const existingChildIndex = delegation.children.findIndex((child) => child.id === action.id);
    const children = existingChildIndex >= 0
      ? delegation.children.map((child) => (child.id === action.id ? action : child))
      : [...delegation.children, action];
    timeline[delegationIndex] = { ...delegation, children };
    return true;
  };

  actions.forEach((action) => {
    if (action.kind === 'delegation') {
      const existingIndex = timeline.findIndex((item) => item.id === action.id);
      if (existingIndex >= 0) {
        const existing = timeline[existingIndex];
        const children = existing.kind === 'delegation_group' ? existing.children : [];
        timeline[existingIndex] = createDelegationGroup(action, children);
        delegationIndexByAgentId.set(action.agentId, existingIndex);
        return;
      }
      delegationIndexByAgentId.set(action.agentId, timeline.length);
      timeline.push(createDelegationGroup(action));
      return;
    }

    const actionAgentId = 'agentId' in action ? action.agentId : undefined;
    const delegationIndex = actionAgentId ? delegationIndexByAgentId.get(actionAgentId) : undefined;
    if (delegationIndex != null && appendDelegationChild(delegationIndex, action)) {
      return;
    }

    appendTopLevelAction(action);
  });

  return timeline;
}

function formatElapsedTime(milliseconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

function getQuestionTypeLabel(type?: string): string {
  const normalized = (type || '').toLowerCase();
  if (normalized.includes('multiple')) return '多选题';
  if (normalized.includes('text')) return '文本题';
  if (normalized.includes('confirmation')) return '确认';
  return '单选题';
}

function getOptionText(option: unknown): string {
  if (!isRecord(option)) return '';
  return String(option.optionText || option.displayText || option.text || option.label || option.name || option.value || option.optionId || '');
}

function getQuestionOptionId(option: unknown): string {
  if (!isRecord(option)) return '';
  return String(option.optionId || option.id || option.value || '');
}

function formatQuestionMessage(questions: Question[]): string {
  if (questions.length === 0) return 'Jarvis 需要你补充一些信息。';
  if (questions.length === 1) return questions[0].questionText || 'Jarvis 需要你补充一些信息。';
  return `Jarvis 需要你补充 ${questions.length} 个信息。`;
}

function formatQuestionnaireAnswerPrompt(
  questions: Question[],
  answers: UserAnswer[],
  questionnaire?: QuestionnaireArtifact | null,
): string {
  const title = questionnaire?.title?.trim() || '问卷';
  const lines = [
    `我已完成「${title}」作答，请基于这些答案继续处理。`,
    questionnaire?.questionnaireId ? `问卷ID：${questionnaire.questionnaireId}` : '',
    '',
    '用户回答：',
  ].filter((line) => line !== '');

  answers.forEach((answer, index) => {
    const question = questions.find((item) => item.questionId === answer.questionId) || null;
    lines.push(`Q${index + 1}: ${question?.questionText || answer.questionId}`);
    lines.push(`A${index + 1}: ${getAnswerDisplayText(question, answer)}`);
    lines.push('');
  });

  return lines.join('\n').trim();
}

function getAnswerDisplayText(question: Question | null, answer: UserAnswer): string {
  const customInput = answer.customInput?.trim();
  if (answer.skipped) return '已跳过';

  const selectedIds = answer.selectedOptionIds?.filter(Boolean) || [];

  const optionTextById = new Map<string, string>();
  question?.options?.forEach((option) => {
    const optionId = getQuestionOptionId(option);
    if (optionId) optionTextById.set(optionId, getOptionText(option));
  });

  const selectedText = selectedIds
    .map((optionId) => optionTextById.get(optionId) || optionId)
    .join('、');

  if (selectedText && customInput) return `${selectedText}；${customInput}`;
  if (customInput) return customInput;
  if (selectedText) return selectedText;
  return '已提交';
}

function createQuestionTraceMessage(
  questions: Question[],
  pendingId?: string,
  id?: string,
  questionnaire?: QuestionnaireArtifact,
): ChatMessage {
  return {
    id: id || crypto.randomUUID(),
    role: 'assistant',
    content: formatQuestionMessage(questions),
    timestamp: new Date(),
    questionnaire,
    questionTrace: {
      kind: 'ask_user_question',
      pendingId,
      questionnaireId: questionnaire?.questionnaireId,
      title: questionnaire?.title,
      questions,
    },
  };
}

function normalizeHistoryAction(item: unknown): AssistantActionItem | null {
  if (!isRecord(item)) return null;

  const kind = typeof item.kind === 'string' ? item.kind : '';
  const status = typeof item.status === 'string' ? item.status : undefined;
  const base = {
    ...item,
    id: typeof item.id === 'string' && item.id ? item.id : `history-action-${crypto.randomUUID()}`,
    timestamp: typeof item.timestamp === 'string' ? item.timestamp : undefined,
  };

  if (kind === 'checkpoint') {
    return {
      ...base,
      kind: 'checkpoint',
      title: typeof item.title === 'string' ? item.title : '更新',
      content: typeof item.content === 'string' ? item.content : '',
      phase: typeof item.phase === 'string' ? item.phase : undefined,
      status: typeof item.status === 'string' ? item.status : undefined,
    } as AssistantActionItem;
  }

  if (kind === 'artifact_ready') {
    return {
      ...base,
      kind: 'artifact_ready',
      artifactType: typeof item.artifactType === 'string' ? item.artifactType : 'artifact',
      title: typeof item.title === 'string' ? item.title : '产物已生成',
      summary: typeof item.summary === 'string' ? item.summary : undefined,
      status: typeof item.status === 'string' ? item.status : 'success',
    } as AssistantActionItem;
  }

  if (kind === 'delegation') {
    return {
      ...base,
      kind: 'delegation',
      agentId: typeof item.agentId === 'string' ? item.agentId : '',
      agentLabel: typeof item.agentLabel === 'string' ? item.agentLabel : 'Sub Agent',
      agentType: typeof item.agentType === 'string' ? item.agentType : undefined,
      title: typeof item.title === 'string' ? item.title : '委托子 Agent',
      task: typeof item.task === 'string' ? item.task : undefined,
      status: status === 'success' || status === 'failed' || status === 'pending' || status === 'running' ? status : 'success',
      summary: typeof item.summary === 'string' ? item.summary : undefined,
      error: typeof item.error === 'string' ? item.error : undefined,
      turnCount: typeof item.turnCount === 'number' ? item.turnCount : undefined,
      maxTurns: typeof item.maxTurns === 'number' ? item.maxTurns : undefined,
      inputTokens: typeof item.inputTokens === 'number' ? item.inputTokens : undefined,
      outputTokens: typeof item.outputTokens === 'number' ? item.outputTokens : undefined,
    } as AssistantActionItem;
  }

  if (kind === 'user_question') {
    return {
      ...base,
      kind: 'user_question',
      title: typeof item.title === 'string' ? item.title : '需要你补充信息',
      summary: typeof item.summary === 'string' ? item.summary : undefined,
      questionCount: typeof item.questionCount === 'number' ? item.questionCount : 1,
      pendingId: typeof item.pendingId === 'string' ? item.pendingId : undefined,
      toolCallId: typeof item.toolCallId === 'string' ? item.toolCallId : undefined,
      status: 'pending',
    } as AssistantActionItem;
  }

  if (kind === 'tool_use' || typeof item.toolName === 'string') {
    return {
      ...base,
      kind: 'tool_use',
      toolName: typeof item.toolName === 'string' ? item.toolName : 'tool',
      title: typeof item.title === 'string' ? item.title : typeof item.toolName === 'string' ? item.toolName : '工具调用',
      description: typeof item.description === 'string' ? item.description : undefined,
      status: status === 'success' || status === 'failed' || status === 'blocked' || status === 'pending' || status === 'running' ? status : 'success',
      summary: typeof item.summary === 'string' ? item.summary : undefined,
      error: typeof item.error === 'string' ? item.error : undefined,
      groupId: typeof item.groupId === 'string' ? item.groupId : undefined,
      groupKind: typeof item.groupKind === 'string' ? item.groupKind : undefined,
      groupTitle: typeof item.groupTitle === 'string' ? item.groupTitle : undefined,
      groupSummary: typeof item.groupSummary === 'string' ? item.groupSummary : undefined,
      agentId: typeof item.agentId === 'string' ? item.agentId : undefined,
      agentScope: item.agentScope === 'main' || item.agentScope === 'sub' ? item.agentScope : undefined,
      agentLabel: typeof item.agentLabel === 'string' ? item.agentLabel : undefined,
    } as AssistantActionItem;
  }

  return null;
}

function normalizeHistoryActions(actions: unknown): AssistantActionItem[] | undefined {
  if (!Array.isArray(actions)) return undefined;
  const normalized = actions
    .map(normalizeHistoryAction)
    .filter((action): action is AssistantActionItem => Boolean(action));
  return normalized.length ? normalized : undefined;
}

function createQuestionnaireAnswerTraceMessage(questions: Question[], answers: UserAnswer[]): ChatMessage {
  const answerItems = answers.map((answer) => {
    const question = questions.find((item) => item.questionId === answer.questionId) || null;
    return {
      questionText: question?.questionText,
      answerText: getAnswerDisplayText(question, answer),
    };
  });
  const content = answerItems
    .map((item) => `${item.questionText ? `${item.questionText}：` : ''}${item.answerText}`)
    .join('\n');

  return {
    id: crypto.randomUUID(),
    role: 'user',
    content,
    timestamp: new Date(),
    answerTrace: {
      kind: 'ask_user_answer',
      answerText: content,
      answers: answerItems,
    },
  };
}

function parseAnswerTraceText(text: string): { answers: Array<{ questionText?: string; answerText: string }> } | null {
  const trimmed = text.trim();
  if (!trimmed.includes('用户回答：')) return null;

  const answers: Array<{ questionText?: string; answerText: string }> = [];
  const blocks = trimmed
    .slice(trimmed.indexOf('用户回答：') + '用户回答：'.length)
    .split(/\n\s*\n/)
    .map((block) => block.trim())
    .filter(Boolean);

  blocks.forEach((block) => {
    const questionMatch = block.match(/^Q\d*:\s*(.+)$/m);
    const answerMatch = block.match(/^A\d*:\s*(.+)$/m);
    const answerText = answerMatch?.[1]?.trim();
    if (answerText) {
      answers.push({
        questionText: questionMatch?.[1]?.trim(),
        answerText,
      });
    }
  });

  if (answers.length === 0) return null;

  return {
    answers,
  };
}

function normalizeHistory(raw: unknown): ChatMessage[] {
  const list = Array.isArray(raw)
    ? raw
    : isRecord(raw) && Array.isArray(raw.messages)
      ? raw.messages
      : [];

  const messages: ChatMessage[] = [];
  let pendingMindmap: MindmapData | null = null;
  let pendingArtifacts: StructuredArtifacts | null = null;
  let pendingQuestionTrace: ChatMessage | null = null;

  const appendMindmapPlaceholder = (index: number) => {
    if (!pendingMindmap) return;

    messages.push({
      id: `history-mindmap-${index}`,
      role: 'assistant',
      content: '已生成思维导图，可在工作台打开查看。',
      timestamp: new Date(),
      actions: undefined,
      mindmap: pendingMindmap,
    });
    pendingMindmap = null;
  };

  const appendStructuredPlaceholder = (index: number) => {
    if (!pendingArtifacts) return;

    if (pendingArtifacts.questionnaire) {
      messages.push(createQuestionTraceMessage(
        pendingArtifacts.questionnaire.questions,
        undefined,
        `history-questionnaire-${index}`,
        pendingArtifacts.questionnaire,
      ));
      pendingArtifacts = null;
      return;
    }

    const content = pendingArtifacts.resume
      ? '已生成简历，可在工作台打开预览和编辑。'
      : pendingArtifacts.optimizeResult
        ? '已生成简历优化分析，可在工作台查看。'
        : 'Jarvis 需要你补充一些信息，请点击作答。';

    messages.push({
      id: `history-resume-${index}`,
      role: 'assistant',
      content,
      timestamp: new Date(),
      actions: undefined,
      resumeData: pendingArtifacts.resume,
      optimizeResult: pendingArtifacts.optimizeResult,
      questionnaire: pendingArtifacts.questionnaire,
    });
    pendingArtifacts = null;
  };

  const appendPendingQuestionTrace = (index: number) => {
    if (!pendingQuestionTrace) return;
    messages.push({
      ...pendingQuestionTrace,
      id: pendingQuestionTrace.id || `history-question-${index}`,
    });
    pendingQuestionTrace = null;
  };

  list.forEach((item, index) => {
    if (!isRecord(item)) return;

    const type = String(item.role || item.type || '').toLowerCase();
    const rawContent = item.content;
    const content = typeof rawContent === 'string'
      ? rawContent
      : rawContent == null
        ? ''
        : String(rawContent);
    const toolName = typeof item.toolName === 'string' ? item.toolName : '';
    const actions = normalizeHistoryActions(item.actions);
    const explicitMindmap = parseMindmapArtifactEnvelopes(item.artifacts) ||
      parseMindmapData(item.mindmapData) ||
      parseMindmapData(item.mindmap);
    const toolMindmap = toolName === 'generateMindmap' && content ? parseMindmapData(content) : null;
    const mindmap = explicitMindmap || toolMindmap;
    const envelopeArtifacts = parseArtifactEnvelopes(item.artifacts);
    const explicitArtifacts = mergeArtifacts(envelopeArtifacts, mergeArtifacts(
        mergeArtifacts(
          mergeArtifacts(parseStructuredArtifactsData(item.resumeData), parseStructuredArtifactsData(item.resume)),
          parseStructuredArtifactsData(item.optimizeResult),
        ),
        parseStructuredArtifactsData(item.questionnaireData || item.questionnaire),
    ));
    const artifacts = explicitArtifacts;

    if (type.includes('tool')) {
      if (mindmap) pendingMindmap = mindmap;
      if (artifacts) pendingArtifacts = artifacts;
      const answerTrace = parseAnswerTraceText(content);
      if (answerTrace) {
        appendPendingQuestionTrace(index);
        const answerText = answerTrace.answers
          .map((answer) => `${answer.questionText ? `${answer.questionText}：` : ''}${answer.answerText}`)
          .join('\n');
        messages.push({
          id: typeof item.id === 'string' && item.id ? item.id : `history-answer-${index}`,
          role: 'user',
          content: answerText,
          timestamp: new Date(typeof item.timestamp === 'string' || typeof item.timestamp === 'number' ? item.timestamp : Date.now()),
          answerTrace: {
            kind: 'ask_user_answer',
            questionText: answerTrace.answers[0]?.questionText,
            answerText,
            answers: answerTrace.answers,
          },
        });
      }
      return;
    }

    if (type.includes('system')) return;

    const role = type.includes('user') ? 'user' : 'assistant';
    if (role === 'user') {
      appendPendingQuestionTrace(index);
      appendMindmapPlaceholder(index);
      appendStructuredPlaceholder(index);
      if (!content) return;
      messages.push({
        id: typeof item.id === 'string' && item.id ? item.id : `history-${index}`,
        role,
        content,
        timestamp: new Date(typeof item.timestamp === 'string' || typeof item.timestamp === 'number' ? item.timestamp : Date.now()),
      });
      return;
    }

    const assistantMindmap = mindmap || pendingMindmap;
    const assistantArtifacts = mergeArtifacts(artifacts, pendingArtifacts);
    if (assistantArtifacts?.questionnaire) {
      pendingQuestionTrace = null;
      messages.push(createQuestionTraceMessage(
        assistantArtifacts.questionnaire.questions,
        undefined,
        typeof item.id === 'string' && item.id ? item.id : `history-questionnaire-${index}`,
        assistantArtifacts.questionnaire,
      ));
      if (actions?.length) {
        messages[messages.length - 1] = {
          ...messages[messages.length - 1],
          actions,
        };
      }
      pendingArtifacts = null;
      if (assistantMindmap) pendingMindmap = null;
      return;
    }
    const questionPayload = Array.isArray(item.questions)
      ? item.questions as Question[]
      : Array.isArray(item.pendingQuestions)
        ? item.pendingQuestions as Question[]
        : null;
    if (questionPayload?.length) {
      if (!assistantMindmap && !assistantArtifacts) {
        pendingQuestionTrace = createQuestionTraceMessage(
          questionPayload,
          typeof item.pendingId === 'string' ? item.pendingId : undefined,
          typeof item.id === 'string' && item.id ? item.id : `history-question-${index}`,
        );
        if (actions?.length) {
          pendingQuestionTrace.actions = actions;
        }
        return;
      }
    }

    const displayContent = getArtifactAwareDisplayContent(content, assistantArtifacts, assistantMindmap);

    if (!displayContent && !assistantMindmap && !assistantArtifacts && !actions?.length) return;

    messages.push({
      id: typeof item.id === 'string' && item.id ? item.id : `history-${index}`,
      role,
      content: displayContent || (actions?.length ? '' : '已生成内容，可在工作台打开查看。'),
      timestamp: new Date(typeof item.timestamp === 'string' || typeof item.timestamp === 'number' ? item.timestamp : Date.now()),
      actions,
        mindmap: assistantMindmap || undefined,
        resumeData: assistantArtifacts?.resume,
      optimizeResult: assistantArtifacts?.optimizeResult,
      questionnaire: assistantArtifacts?.questionnaire,
      questionTrace: questionPayload?.length
        ? {
            kind: 'ask_user_question',
            pendingId: typeof item.pendingId === 'string' ? item.pendingId : undefined,
            questions: questionPayload,
          }
        : undefined,
    });
    if (assistantMindmap) pendingMindmap = null;
    if (assistantArtifacts) pendingArtifacts = null;
    if (questionPayload?.length) pendingQuestionTrace = null;
  });

  appendPendingQuestionTrace(list.length);
  appendMindmapPlaceholder(list.length);
  appendStructuredPlaceholder(list.length);
  return messages;
}

function createAssistantError(content: string): ChatMessage {
  return {
    id: crypto.randomUUID(),
    role: 'assistant',
    content,
    timestamp: new Date(),
  };
}

function isExpiredSessionHistoryError(error: unknown, message: string): boolean {
  const status = error instanceof ApiError ? error.status : undefined;
  return status === 400 ||
    message.includes('会话不存在') ||
    message.includes('已过期') ||
    message.includes('Bad Request');
}

function getMindmapTabTitle(mindmap: MindmapData): string {
  const firstMeaningfulLine = mindmap.markdown
    .split('\n')
    .map((line) => line.trim())
    .find((line) => line.length > 0);

  const title = (firstMeaningfulLine || '思维导图')
    .replace(/^#{1,6}\s*/, '')
    .replace(/^[-*+]\s*/, '')
    .replace(/\*\*/g, '')
    .trim();

  return title.length > 14 ? `${title.slice(0, 14)}...` : title || '思维导图';
}

function getResumeTabTitle(resume: ResumeVO): string {
  const name = resume.basicInfo?.name?.trim();
  const position = resume.basicInfo?.position?.trim() || resume.jobIntention?.position?.trim();
  const title = name
    ? `${name}的简历`
    : position
      ? `${position}简历`
      : '简历预览';

  return title.length > 16 ? `${title.slice(0, 16)}...` : title;
}

function getOptimizeTabTitle(result: OptimizeResult): string {
  const score = typeof result.matchScore === 'number' && Number.isFinite(result.matchScore)
    ? `匹配度 ${Math.round(result.matchScore)}`
    : '优化分析';
  return score;
}

function buildResumeOptimizePrompt(resume: ResumeVO, request: ResumeOptimizeRequest): string {
  return [
    '请基于下面的简历数据进行简历优化。',
    '',
    '要求：',
    '1. 如需要指南，请调用简历优化指南工具。',
    '2. 优先针对目标岗位和 JD 分析匹配度、缺口、优化建议。',
    '3. 最终必须返回一个 JSON 对象，不要包裹 Markdown 代码块。',
    '4. JSON 格式优先为 {"type":"optimize_result","matchScore":0-100,"matchAnalysis":{"matchedSkills":[],"missingSkills":[],"experienceMatch":"","educationMatch":"","matchedBonus":[]},"suggestions":[],"highlights":[],"optimizedResume":{...可选优化后简历...}}。',
    '5. 如果你直接产出优化后的完整简历，也可以返回 {"type":"resume","resume":{...}}。',
    '',
    `目标岗位：${request.targetPosition || resume.jobIntention?.position || resume.basicInfo?.position || '未指定'}`,
    `优化范围：${request.scope || 'full'}`,
    `优化目标：${request.goal || '提升简历质量和岗位匹配度'}`,
    '',
    'JD / 岗位要求：',
    request.jobDescription || '未提供 JD，请做通用优化。',
    '',
    '当前简历 JSON：',
    JSON.stringify(resume, null, 2),
  ].join('\n');
}

const MIN_WORKBENCH_PANEL_WIDTH = 420;
const DEFAULT_WORKBENCH_PANEL_WIDTH = 920;
const MIN_MAIN_WITH_WORKBENCH_WIDTH = 460;
const ACTIVE_SESSION_STORAGE_KEY = 'jarvis.activeSessionId';
const clampNumber = (value: number, min: number, max: number) => Math.min(Math.max(value, min), max);
type StatusPanelTab = 'tasks' | 'main' | 'agents';
type WorkbenchMode = 'workbench' | 'workspace';
type ResourceBrowserVariant = 'knowledge-base' | 'workspace';
type RunProcessState = {
  runId: string;
  stepMap: Record<string, RunStepNode>;
  rootIds: string[];
  startedAt: number;
  latestEventAt: number;
};
type MainAgentState = {
  visible: boolean;
  phase: 'connecting' | 'connected' | 'running' | 'success' | 'failed' | 'pending';
  detail: string;
  startedAt: number | null;
  endedAt: number | null;
  runId?: string;
};
type WorkbenchTabBase = {
  id: string;
  title: string;
  createdAt: number;
};

type WorkbenchTab = WorkbenchTabBase & (
  | { type: 'mindmap'; mindmap: MindmapData }
  | { type: 'resume'; resume: ResumeVO; optimizeResult?: OptimizeResult }
  | { type: 'optimize_result'; result: OptimizeResult }
);

export function ChatInterface() {
  const createIdleMainAgentState = (): MainAgentState => ({
    visible: false,
    phase: 'connecting',
    detail: '',
    startedAt: null,
    endedAt: null,
  });

  const [restoreSessionId, setRestoreSessionId] = useState<string | null>(
    () => localStorage.getItem(ACTIVE_SESSION_STORAGE_KEY)?.trim() || null,
  );
  const [messagesBySession, setMessagesBySession] = useState<Record<string, ChatMessage[]>>({});
  const [input, setInput] = useState('');
  const [loadingSessionIds, setLoadingSessionIds] = useState<string[]>([]);
  const [isHistoryLoading, setIsHistoryLoading] = useState(false);
  const [sessionId, setSessionId] = useState<string>('');
  const [isChatMode, setIsChatMode] = useState(false);
  const [streamingMessageId, setStreamingMessageId] = useState<string | null>(null);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [isSidebarOpen, setIsSidebarOpen] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [pendingQuestion, setPendingQuestion] = useState<Question | null>(null);
  const [pendingQuestions, setPendingQuestions] = useState<Question[]>([]);
  const [, setPendingId] = useState('');
  const [activeQuestionnaire, setActiveQuestionnaire] = useState<QuestionnaireArtifact | null>(null);
  const [isQuestionDialogOpen, setIsQuestionDialogOpen] = useState(false);
  const [isKnowledgeBaseOpen, setIsKnowledgeBaseOpen] = useState(false);
  const [isSkillsPanelOpen, setIsSkillsPanelOpen] = useState(false);
  const [activeSkillsTab, setActiveSkillsTab] = useState<SkillsPanelTab>('existing');
  const [skillInstructionDraft, setSkillInstructionDraft] = useState('');
  const [uploadedSkill, setUploadedSkill] = useState<SkillUploadResponse | null>(null);
  const [isUploadingSkill, setIsUploadingSkill] = useState(false);
  const [isDraggingSkillFile, setIsDraggingSkillFile] = useState(false);
  const [skillUploadError, setSkillUploadError] = useState<string | null>(null);
  const [skillUploadFileName, setSkillUploadFileName] = useState('');
  const [existingSkills, setExistingSkills] = useState<ExistingSkillItem[]>([]);
  const [selectedExistingSkillId, setSelectedExistingSkillId] = useState<string>('');
  const [isExistingSkillsLoading, setIsExistingSkillsLoading] = useState(false);
  const [existingSkillsError, setExistingSkillsError] = useState<string | null>(null);
  const [knowledgeBaseFiles, setKnowledgeBaseFiles] = useState<File[]>([]);
  const [knowledgeBaseSourceUrl, setKnowledgeBaseSourceUrl] = useState('');
  const [knowledgeBaseTextFileName, setKnowledgeBaseTextFileName] = useState('');
  const [knowledgeBaseText, setKnowledgeBaseText] = useState('');
  const [knowledgeBaseFeedback, setKnowledgeBaseFeedback] = useState<string | null>(null);
  const [knowledgeBaseUploadSummary, setKnowledgeBaseUploadSummary] = useState<string | null>(null);
  const [isDraggingKnowledgeBaseFiles, setIsDraggingKnowledgeBaseFiles] = useState(false);
  const [quotedResourceUris, setQuotedResourceUris] = useState<string[]>([]);
  const [resourceDirectoryPath, setResourceDirectoryPath] = useState<string>(ROOT_RESOURCE_PATH);
  const [resourceDirectoryInput, setResourceDirectoryInput] = useState<string>(ROOT_RESOURCE_PATH);
  const [resourceItems, setResourceItems] = useState<ResourceItemResponse[]>([]);
  const [isResourceLoading, setIsResourceLoading] = useState(false);
  const [resourceError, setResourceError] = useState<string | null>(null);
  const [resourceBrowserVariant, setResourceBrowserVariant] = useState<ResourceBrowserVariant>('knowledge-base');
  const [selectedResourceUri, setSelectedResourceUri] = useState<string>('');
  const [selectedResourceDetail, setSelectedResourceDetail] = useState<ResourceDetailResponse | null>(null);
  const [isResourceDetailLoading, setIsResourceDetailLoading] = useState(false);
  const [isDeletingResource, setIsDeletingResource] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [wallpaperUrl, setWallpaperUrl] = useState<string | null>(() => {
    if (typeof window === 'undefined') return null;
    return window.localStorage.getItem(WALLPAPER_STORAGE_KEY);
  });
  const [currentUser, setCurrentUser] = useState<AuthUser | null>(() => getStoredAuth()?.user || null);
  const [authChecked, setAuthChecked] = useState(false);
  const [workbenchTabs, setWorkbenchTabs] = useState<WorkbenchTab[]>([]);
  const [activeWorkbenchTabId, setActiveWorkbenchTabId] = useState<string | null>(null);
  const [isWorkbenchOpen, setIsWorkbenchOpen] = useState(false);
  const [activeWorkbenchMode, setActiveWorkbenchMode] = useState<WorkbenchMode>('workbench');
  const [workbenchPanelWidth, setWorkbenchPanelWidth] = useState(DEFAULT_WORKBENCH_PANEL_WIDTH);
  const [isResizingWorkbench, setIsResizingWorkbench] = useState(false);
  const [optimizingWorkbenchTabId, setOptimizingWorkbenchTabId] = useState<string | null>(null);
  const [isChatScrolling, setIsChatScrolling] = useState(false);
  const [openSessionMenu, setOpenSessionMenu] = useState<SessionMenuPosition | null>(null);
  const [renameSessionTarget, setRenameSessionTarget] = useState<Session | null>(null);
  const [renameTitle, setRenameTitle] = useState('');
  const [isStatusPanelOpen, setIsStatusPanelOpen] = useState(false);
  const [activeStatusTab, setActiveStatusTab] = useState<StatusPanelTab>('tasks');
  const [taskPlan, setTaskPlan] = useState<TaskItem[]>([]);
  const [taskProgress, setTaskProgress] = useState<TaskProgress | undefined>();
  const [runProcess, setRunProcess] = useState<RunProcessState | null>(null);
  const [mainAgentState, setMainAgentState] = useState<MainAgentState>(createIdleMainAgentState);
  const [attachedFile, setAttachedFile] = useState<FileUploadResponse | null>(null);
  const [isUploadingFile, setIsUploadingFile] = useState(false);
  const [isDraggingFile, setIsDraggingFile] = useState(false);
  const [runProcessClock, setRunProcessClock] = useState(Date.now());

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const heroTextareaRef = useRef<HTMLTextAreaElement>(null);
  const chatTextareaRef = useRef<HTMLTextAreaElement>(null);
  const renameInputRef = useRef<HTMLInputElement>(null);
  const sessionMenuRef = useRef<HTMLDivElement | null>(null);
  const sessionMenuTriggerRefs = useRef<Record<string, HTMLButtonElement | null>>({});
  const chatScrollTimerRef = useRef<number | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const skillFileInputRef = useRef<HTMLInputElement | null>(null);
  const knowledgeBaseFileInputRef = useRef<HTMLInputElement | null>(null);
  const resourceAutoLoadedUserRef = useRef<string | null>(null);
  const workspaceAutoLoadedUserRef = useRef<string | null>(null);
  const sessionIdRef = useRef('');
  const { sendMessage: sendStreamMessage, disconnect } = useChatStream();

  const isLoading = loadingSessionIds.includes(sessionId);
  const messages = messagesBySession[sessionId] || [];
  const selectedExistingSkill = existingSkills.find((skill) => skill.id === selectedExistingSkillId) || existingSkills[0] || null;
  const selectedResource = resourceItems.find((item) => item.uri === selectedResourceUri) || resourceItems[0] || null;

  const loadResourceDirectory = useCallback(async (
    targetPath: string,
    variant: ResourceBrowserVariant = 'knowledge-base',
  ) => {
    const rootPath = variant === 'workspace' ? ROOT_WORKSPACE_PATH : ROOT_RESOURCE_PATH;
    const normalizedTargetPath = normalizeResourceDirectoryPath(targetPath, rootPath);
    setIsResourceLoading(true);
    setResourceError(null);
    setResourceBrowserVariant(variant);
    try {
      const items = variant === 'workspace'
        ? await resourceService.listWorkspace(normalizedTargetPath)
        : await resourceService.listResources(normalizedTargetPath);
      setResourceItems(items);
      setResourceDirectoryPath(normalizedTargetPath);
      setResourceDirectoryInput(normalizedTargetPath);
      setSelectedResourceUri(items[0]?.uri || '');
      setSelectedResourceDetail(null);
    } catch (error) {
      const message = error instanceof Error ? error.message : '加载资源目录失败';
      setResourceError(message);
      setResourceItems([]);
    } finally {
      setIsResourceLoading(false);
    }
  }, []);

  const setSessionMessages = useCallback((
    targetSessionId: string,
    updater: React.SetStateAction<ChatMessage[]>,
  ) => {
    if (!targetSessionId) return;

    setMessagesBySession((currentMessagesBySession) => {
      const currentMessages = currentMessagesBySession[targetSessionId] || [];
      const nextMessages =
        typeof updater === 'function'
          ? (updater as (previousMessages: ChatMessage[]) => ChatMessage[])(currentMessages)
          : updater;

      return {
        ...currentMessagesBySession,
        [targetSessionId]: nextMessages,
      };
    });
  }, []);

  const setSessionLoading = useCallback((targetSessionId: string, loading: boolean) => {
    if (!targetSessionId) return;
    setLoadingSessionIds((currentSessionIds) => {
      const hasSession = currentSessionIds.includes(targetSessionId);
      if (loading) return hasSession ? currentSessionIds : [...currentSessionIds, targetSessionId];
      if (!hasSession) return currentSessionIds;
      return currentSessionIds.filter((item) => item !== targetSessionId);
    });
  }, []);

  const isVisibleSession = useCallback((targetSessionId: string) => {
    return sessionIdRef.current === targetSessionId;
  }, []);

  useEffect(() => {
    sessionIdRef.current = sessionId;
  }, [sessionId]);

  const persistSessionId = useCallback((nextSessionId: string) => {
    if (!nextSessionId) return;
    localStorage.setItem(ACTIVE_SESSION_STORAGE_KEY, nextSessionId);
    setSessionId(nextSessionId);
  }, []);

  const clearPersistedSessionId = useCallback(() => {
    localStorage.removeItem(ACTIVE_SESSION_STORAGE_KEY);
  }, []);

  const updateTaskState = useCallback((rawTaskPlan: unknown, progress?: TaskProgress) => {
    const nextTaskPlan = normalizeTaskPlan(rawTaskPlan);
    setTaskPlan(nextTaskPlan);
    setTaskProgress(progress || buildTaskProgress(nextTaskPlan));
  }, []);

  const resetRunProcess = useCallback((runId?: string) => {
    if (!runId) {
      setRunProcess(null);
      return;
    }

    setRunProcess({
      runId,
      stepMap: {},
      rootIds: [],
      startedAt: Date.now(),
      latestEventAt: Date.now(),
    });
  }, []);

  const resetMainAgentState = useCallback(() => {
    setMainAgentState(createIdleMainAgentState());
  }, []);

  const startMainAgentState = useCallback(() => {
    const now = Date.now();
    setMainAgentState({
      visible: true,
      phase: 'connecting',
      detail: 'Jarvis 连接中',
      startedAt: now,
      endedAt: null,
    });
    setRunProcessClock(now);
  }, []);

  const markMainAgentConnected = useCallback((runId?: string) => {
    setMainAgentState((current) => ({
      visible: true,
      phase: 'connected',
      detail: 'Jarvis 已连接',
      startedAt: current.startedAt ?? Date.now(),
      endedAt: null,
      runId: runId || current.runId,
    }));
  }, []);

  const updateMainAgentRunning = useCallback((payload: RunStepPayload) => {
    setMainAgentState((current) => ({
      visible: true,
      phase: 'running',
      detail: payload.title || payload.name || 'Jarvis 处理中',
      startedAt: current.startedAt ?? Date.now(),
      endedAt: null,
      runId: payload.runId || current.runId,
    }));
  }, []);

  const finishMainAgentState = useCallback((phase: MainAgentState['phase'], detail: string) => {
    const now = Date.now();
    setMainAgentState((current) => ({
      visible: current.visible || phase !== 'success',
      phase,
      detail,
      startedAt: current.startedAt,
      endedAt: current.startedAt ? now : current.endedAt,
      runId: current.runId,
    }));
    setRunProcessClock(now);
  }, []);

  const upsertRunStep = useCallback((payload: RunStepPayload) => {
    setRunProcess((current) => {
      const activeRunId = payload.runId;
      const previous = current?.runId === activeRunId
        ? current
        : {
            runId: activeRunId,
            stepMap: {},
            rootIds: [],
            startedAt: Date.now(),
            latestEventAt: Date.now(),
          };

      const previousNode = previous.stepMap[payload.id];
      const previousParentId = previousNode?.parentId ?? null;
      const nextParentId = payload.parentId ?? null;
      const firstSeenAt = previousNode?.firstSeenAt ?? Date.now();
      const lastEventAt = Date.now();

      const stepMap: Record<string, RunStepNode> = { ...previous.stepMap };
      const rootIds = [...previous.rootIds];

      const detachFromParent = (parentId: string | null, childId: string) => {
        if (!parentId) return;
        const parent = stepMap[parentId];
        if (!parent) return;
        stepMap[parentId] = {
          ...parent,
          children: parent.children.filter((child) => child.id !== childId),
        };
      };

      const removeRootId = (childId: string) => {
        const index = rootIds.indexOf(childId);
        if (index >= 0) rootIds.splice(index, 1);
      };

      if (previousNode && previousParentId !== nextParentId) {
        detachFromParent(previousParentId, payload.id);
      }

      const nextNode: RunStepNode = {
        ...(previousNode || {
          children: [],
          firstSeenAt,
          lastEventAt,
        }),
        ...payload,
        parentId: nextParentId,
        children: previousNode?.children || [],
        firstSeenAt,
        lastEventAt,
      };

      stepMap[payload.id] = nextNode;

      if (nextParentId) {
        removeRootId(payload.id);
        const parent = stepMap[nextParentId];
        if (parent && !parent.children.some((child) => child.id === payload.id)) {
          stepMap[nextParentId] = {
            ...parent,
            children: [...parent.children, nextNode].sort((a, b) => a.firstSeenAt - b.firstSeenAt),
          };
        } else if (parent) {
          stepMap[nextParentId] = {
            ...parent,
            children: parent.children
              .map((child) => (child.id === payload.id ? nextNode : child))
              .sort((a, b) => a.firstSeenAt - b.firstSeenAt),
          };
        }
      } else {
        if (!rootIds.includes(payload.id)) rootIds.push(payload.id);
      }

      Object.keys(stepMap).forEach((nodeId) => {
        const node = stepMap[nodeId];
        if (!node.children.length) return;
        stepMap[nodeId] = {
          ...node,
          children: node.children
            .map((child) => stepMap[child.id] || child)
            .sort((a, b) => a.firstSeenAt - b.firstSeenAt),
        };
      });

      return {
        runId: activeRunId,
        stepMap,
        rootIds: rootIds.sort((a, b) => (stepMap[a]?.firstSeenAt || 0) - (stepMap[b]?.firstSeenAt || 0)),
        startedAt: previous.startedAt,
        latestEventAt: lastEventAt,
      };
    });

  }, []);

  const upsertAssistantAction = useCallback((targetSessionId: string, aiMessageId: string, payload: ToolUseActionPayload) => {
    setSessionMessages(targetSessionId, (prev) =>
      prev.map((message) => {
        if (message.id !== aiMessageId) return message;

        const action: AssistantActionItem = {
          ...payload,
          kind: 'tool_use',
        };
        const currentActions = message.actions || [];
        const existingIndex = currentActions.findIndex((item) => item.id === action.id);
        const nextActions: AssistantActionItem[] = existingIndex >= 0
          ? currentActions.map((item) => (item.id === action.id ? action : item))
          : [...currentActions, action];

        return {
          ...message,
          actions: nextActions,
        };
      }),
    );
  }, [setSessionMessages]);

  const upsertAssistantCheckpoint = useCallback((targetSessionId: string, aiMessageId: string, payload: AssistantCheckpointPayload) => {
    setSessionMessages(targetSessionId, (prev) =>
      prev.map((message) => {
        if (message.id !== aiMessageId) return message;

        const action: AssistantActionItem = {
          ...payload,
          kind: 'checkpoint',
        };
        const currentActions = message.actions || [];
        const existingIndex = currentActions.findIndex((item) => item.id === action.id);
        const nextActions: AssistantActionItem[] = existingIndex >= 0
          ? currentActions.map((item) => (item.id === action.id ? action : item))
          : [...currentActions, action];

        return {
          ...message,
          actions: nextActions,
        };
      }),
    );
  }, [setSessionMessages]);

  const upsertArtifactReady = useCallback((targetSessionId: string, aiMessageId: string, payload: ArtifactReadyPayload) => {
    setSessionMessages(targetSessionId, (prev) =>
      prev.map((message) => {
        if (message.id !== aiMessageId) return message;

        const action: AssistantActionItem = {
          ...payload,
          kind: 'artifact_ready',
        };
        const currentActions = message.actions || [];
        const existingIndex = currentActions.findIndex((item) => item.id === action.id);
        const nextActions: AssistantActionItem[] = existingIndex >= 0
          ? currentActions.map((item) => (item.id === action.id ? action : item))
          : [...currentActions, action];

        return {
          ...message,
          actions: nextActions,
        };
      }),
    );
  }, [setSessionMessages]);

  const upsertDelegationAction = useCallback((targetSessionId: string, aiMessageId: string, payload: DelegationActionPayload) => {
    setSessionMessages(targetSessionId, (prev) =>
      prev.map((message) => {
        if (message.id !== aiMessageId) return message;

        const action: AssistantActionItem = {
          ...payload,
          kind: 'delegation',
        };
        const currentActions = message.actions || [];
        const existingIndex = currentActions.findIndex((item) => item.id === action.id);
        const nextActions: AssistantActionItem[] = existingIndex >= 0
          ? currentActions.map((item) => (item.id === action.id ? action : item))
          : [...currentActions, action];

        return {
          ...message,
          actions: nextActions,
        };
      }),
    );
  }, [setSessionMessages]);

  const resizeTextarea = useCallback((textarea: HTMLTextAreaElement | null) => {
    if (!textarea) return;

    const styles = window.getComputedStyle(textarea);
    const minHeight = Number.parseFloat(styles.minHeight) || 28;
    const maxHeight = Number.parseFloat(styles.maxHeight) || Math.min(window.innerHeight * 0.36, 310);

    textarea.style.height = 'auto';
    const scrollHeight = textarea.scrollHeight;
    const nextHeight = Math.min(Math.max(scrollHeight, minHeight), maxHeight);

    textarea.style.height = `${nextHeight}px`;
    textarea.style.overflowY = scrollHeight > maxHeight ? 'auto' : 'hidden';
  }, []);

  const resizeVisibleTextarea = useCallback(() => {
    const textarea = isChatMode ? chatTextareaRef.current : heroTextareaRef.current;
    if (!textarea) return;
    requestAnimationFrame(() => resizeTextarea(textarea));
  }, [isChatMode, resizeTextarea]);

  const loadSessions = useCallback(async () => {
    if (!getStoredAuth()) return;
    try {
      const sessionList = await chatService.getAllSessions();
      setSessions(normalizeSessionList(sessionList));
    } catch (error) {
      console.error('加载会话列表失败:', error);
    }
  }, []);

  useEffect(() => {
    if (restoreSessionId) {
      setSessionId(restoreSessionId);
      return;
    }
    persistSessionId(crypto.randomUUID());
  }, [persistSessionId, restoreSessionId]);

  useEffect(() => {
    let cancelled = false;
    const stored = getStoredAuth();

    if (!stored) {
      setAuthChecked(true);
      return;
    }

    authService.me()
      .then((user) => {
        if (!cancelled) {
          setCurrentUser({ ...user, token: stored.token, expire: stored.user.expire });
        }
      })
      .catch(() => {
        if (!cancelled) {
          setCurrentUser(null);
          setSessions([]);
        }
      })
      .finally(() => {
        if (!cancelled) setAuthChecked(true);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (currentUser) {
      loadSessions();
    } else {
      setSessions([]);
    }
  }, [currentUser, loadSessions]);

  useEffect(() => {
    if (!authChecked || !currentUser || !sessionId || !restoreSessionId) return;
    if (messages.length > 0 || isHistoryLoading) return;

    chatService.getSessionHistory(restoreSessionId)
      .then((history) => {
        const chatMessages = normalizeHistory(history);
        setRestoreSessionId(null);
        if (chatMessages.length === 0) return;
        setSessionMessages(sessionId, chatMessages);
        setIsChatMode(true);
      })
      .catch((error) => {
        const message = error instanceof Error ? error.message : '未知错误';
        if (isExpiredSessionHistoryError(error, message)) {
          clearPersistedSessionId();
          persistSessionId(crypto.randomUUID());
        }
        setRestoreSessionId(null);
      });
  }, [authChecked, clearPersistedSessionId, currentUser, isHistoryLoading, messages.length, persistSessionId, restoreSessionId, sessionId, setSessionMessages]);

  useEffect(() => {
    if (!isChatMode) return;
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, isChatMode]);

  useEffect(() => {
    resizeVisibleTextarea();
  }, [input, isChatMode, resizeVisibleTextarea]);

  useEffect(() => {
    window.addEventListener('resize', resizeVisibleTextarea);
    return () => window.removeEventListener('resize', resizeVisibleTextarea);
  }, [resizeVisibleTextarea]);

  useEffect(() => {
    return () => {
      if (chatScrollTimerRef.current) {
        window.clearTimeout(chatScrollTimerRef.current);
      }
      document.body.classList.remove('workbench-resizing');
    };
  }, []);

  useEffect(() => {
    return () => disconnect();
  }, [disconnect]);

  useEffect(() => {
    if (!runProcess) return;

    const timer = window.setInterval(() => {
      setRunProcessClock(Date.now());
    }, 1000);

    return () => window.clearInterval(timer);
  }, [runProcess]);

  useEffect(() => {
    if (!renameSessionTarget) return;
    requestAnimationFrame(() => {
      renameInputRef.current?.focus();
      renameInputRef.current?.select();
    });
  }, [renameSessionTarget]);

  useEffect(() => {
    if (!openSessionMenu) return;

    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node | null;
      if (!target) return;

      if (sessionMenuRef.current?.contains(target)) return;

      const trigger = sessionMenuTriggerRefs.current[openSessionMenu.sessionId];
      if (trigger?.contains(target)) return;

      setOpenSessionMenu(null);
    };

    const handleViewportChange = () => setOpenSessionMenu(null);

    window.addEventListener('pointerdown', handlePointerDown);
    window.addEventListener('resize', handleViewportChange);
    window.addEventListener('scroll', handleViewportChange, true);

    return () => {
      window.removeEventListener('pointerdown', handlePointerDown);
      window.removeEventListener('resize', handleViewportChange);
      window.removeEventListener('scroll', handleViewportChange, true);
    };
  }, [openSessionMenu]);

  useEffect(() => {
    if (!isSkillsPanelOpen) return;

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setIsSkillsPanelOpen(false);
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isSkillsPanelOpen]);

  useEffect(() => {
    if (!isKnowledgeBaseOpen) return;

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setIsKnowledgeBaseOpen(false);
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isKnowledgeBaseOpen]);

  // 知识库面板打开时加载资源目录
  useEffect(() => {
    if (!isKnowledgeBaseOpen) {
      resourceAutoLoadedUserRef.current = null;
      return;
    }

    if (!currentUser || isResourceLoading) return;

    const currentUserKey = String(currentUser.id);
    if (resourceAutoLoadedUserRef.current === currentUserKey && resourceDirectoryPath.startsWith(ROOT_RESOURCE_PATH)) return;

    resourceAutoLoadedUserRef.current = currentUserKey;
    void loadResourceDirectory(ROOT_RESOURCE_PATH, 'knowledge-base');
  }, [currentUser, isKnowledgeBaseOpen, isResourceLoading, loadResourceDirectory, resourceDirectoryPath]);

  // 工作空间模式打开时加载 OpenViking 根目录
  useEffect(() => {
    if (!isWorkbenchOpen || activeWorkbenchMode !== 'workspace' || !currentUser || isResourceLoading) return;
    const currentUserKey = String(currentUser.id);
    if (workspaceAutoLoadedUserRef.current === currentUserKey) return;

    workspaceAutoLoadedUserRef.current = currentUserKey;
    void loadResourceDirectory(ROOT_WORKSPACE_PATH, 'workspace');
  }, [activeWorkbenchMode, currentUser, isResourceLoading, isWorkbenchOpen, loadResourceDirectory]);

  // 选中资源后加载详情
  useEffect(() => {
    if (!selectedResourceUri) return;

    const loadDetail = async () => {
      setIsResourceDetailLoading(true);
      try {
        const detail = resourceBrowserVariant === 'workspace'
          ? await resourceService.getWorkspaceDetail(selectedResourceUri)
          : await resourceService.getResourceDetail(selectedResourceUri);
        setSelectedResourceDetail(detail);
      } catch {
        setSelectedResourceDetail(null);
      } finally {
        setIsResourceDetailLoading(false);
      }
    };

    void loadDetail();
  }, [resourceBrowserVariant, selectedResourceUri]);

  const loadExistingSkills = useCallback(async () => {
    setIsExistingSkillsLoading(true);
    setExistingSkillsError(null);
    try {
      const skills = await skillService.getAll();
      setExistingSkills(skills);
      setSelectedExistingSkillId((currentId) => {
        if (skills.length === 0) return '';
        return skills.some((skill) => skill.id === currentId) ? currentId : skills[0].id;
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : '加载 skills 失败';
      setExistingSkillsError(message);
      setExistingSkills([]);
      setSelectedExistingSkillId('');
    } finally {
      setIsExistingSkillsLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!isSkillsPanelOpen || activeSkillsTab !== 'existing') return;
    void loadExistingSkills();
  }, [activeSkillsTab, isSkillsPanelOpen, loadExistingSkills]);

  const handleNewChat = () => {
    setRestoreSessionId(null);
    const nextSessionId = crypto.randomUUID();
    setSessionMessages(nextSessionId, []);
    persistSessionId(nextSessionId);
    setIsChatMode(false);
    setInput('');
    setPendingQuestion(null);
    setPendingQuestions([]);
    setPendingId('');
    setActiveQuestionnaire(null);
    setIsQuestionDialogOpen(false);
    setStreamingMessageId(null);
    setOptimizingWorkbenchTabId(null);
    setOpenSessionMenu(null);
    setIsStatusPanelOpen(false);
    setActiveStatusTab('tasks');
    setTaskPlan([]);
    setTaskProgress(undefined);
    setRunProcess(null);
    resetMainAgentState();
  };

  const handleAuthenticated = (user: AuthUser) => {
    setCurrentUser(user);
    setIsSettingsOpen(false);
    loadSessions();
  };

  const handleLogout = () => {
    disconnect();
    setLoadingSessionIds([]);
    setRestoreSessionId(null);
    setCurrentUser(null);
    setSessions([]);
    workspaceAutoLoadedUserRef.current = null;
    clearPersistedSessionId();
    handleNewChat();
  };

  const handleWallpaperChange = useCallback((nextWallpaperUrl: string | null) => {
    setWallpaperUrl(nextWallpaperUrl);
    if (typeof window === 'undefined') return;

    if (nextWallpaperUrl) {
      window.localStorage.setItem(WALLPAPER_STORAGE_KEY, nextWallpaperUrl);
      return;
    }

    window.localStorage.removeItem(WALLPAPER_STORAGE_KEY);
  }, []);

  const handleSelectSession = async (selectedSessionId: string) => {
    if (loadingSessionIds.includes(selectedSessionId) && messagesBySession[selectedSessionId]?.length) {
      setOpenSessionMenu(null);
      setRestoreSessionId(null);
      persistSessionId(selectedSessionId);
      setIsChatMode(true);
      setPendingQuestion(null);
      setPendingQuestions([]);
      setPendingId('');
      setActiveQuestionnaire(null);
      setIsQuestionDialogOpen(false);
      setTaskPlan([]);
      setTaskProgress(undefined);
      setIsStatusPanelOpen(false);
      setActiveStatusTab('tasks');
      setRunProcess(null);
      resetMainAgentState();
      return;
    }

    try {
      setOpenSessionMenu(null);
      setIsHistoryLoading(true);
      setRestoreSessionId(null);
      const history = await chatService.getSessionHistory(selectedSessionId);
      const chatMessages = normalizeHistory(history);

      persistSessionId(selectedSessionId);
      setSessionMessages(selectedSessionId, chatMessages);
      setIsChatMode(chatMessages.length > 0);
      setPendingQuestion(null);
      setPendingQuestions([]);
      setPendingId('');
      setActiveQuestionnaire(null);
      setIsQuestionDialogOpen(false);
      setTaskPlan([]);
      setTaskProgress(undefined);
      setIsStatusPanelOpen(false);
      setActiveStatusTab('tasks');
      setRunProcess(null);
      resetMainAgentState();
    } catch (error) {
      const message = error instanceof Error ? error.message : '未知错误';
      console.error('加载会话历史失败:', error);
      if (isExpiredSessionHistoryError(error, message)) {
        setSessions((prev) => prev.filter((session) => session.sessionId !== selectedSessionId));
        if (selectedSessionId === sessionId) {
          setRestoreSessionId(null);
          clearPersistedSessionId();
        }
      }
      setSessionMessages(selectedSessionId, [createAssistantError(`加载会话历史失败：${message}`)]);
      setIsChatMode(true);
      setTaskPlan([]);
      setTaskProgress(undefined);
      setIsStatusPanelOpen(false);
      setActiveStatusTab('tasks');
      setRunProcess(null);
      resetMainAgentState();
    } finally {
      setIsHistoryLoading(false);
    }
  };

  const handleDeleteSession = async (sessionToDelete: string, event: React.MouseEvent) => {
    event.stopPropagation();
    try {
      setOpenSessionMenu(null);
      disconnect(sessionToDelete);
      setSessionLoading(sessionToDelete, false);
      await chatService.deleteSession(sessionToDelete);
      setSessions((prev) => prev.filter((session) => session.sessionId !== sessionToDelete));
      if (sessionToDelete === sessionId) {
        clearPersistedSessionId();
        handleNewChat();
      }
    } catch (error) {
      console.error('删除会话失败:', error);
    }
  };

  const handleOpenSessionMenu = (selectedSessionId: string, event: React.MouseEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.stopPropagation();
    const triggerRect = event.currentTarget.getBoundingClientRect();

    setOpenSessionMenu((current) => {
      if (current?.sessionId === selectedSessionId) {
        return null;
      }

      const menuWidth = 192;
      const menuHeight = 172;
      const gutter = 14;
      const viewportPadding = 12;

      const preferredLeft = triggerRect.right + gutter;
      const fallbackLeft = triggerRect.left - menuWidth - gutter;
      const left =
        preferredLeft + menuWidth <= window.innerWidth - viewportPadding
          ? preferredLeft
          : Math.max(viewportPadding, fallbackLeft);

      const preferredTop = triggerRect.top - 8;
      const clampedTop = clampNumber(
        preferredTop,
        viewportPadding,
        window.innerHeight - menuHeight - viewportPadding,
      );

      return {
        sessionId: selectedSessionId,
        top: clampedTop,
        left,
      };
    });
  };

  const handleStartRenameSession = (session: Session, event: React.MouseEvent) => {
    event.stopPropagation();
    setRenameSessionTarget(session);
    setRenameTitle(getSessionTitle(session));
    setOpenSessionMenu(null);
  };

  const handleRenameSubmit = async (event?: React.FormEvent) => {
    event?.preventDefault();
    if (!renameSessionTarget) return;

    const nextTitle = renameTitle.trim() || '新对话';
    try {
      const updatedSession = await chatService.renameSession(renameSessionTarget.sessionId, nextTitle);
      setSessions((prev) =>
        prev.map((session) =>
          session.sessionId === renameSessionTarget.sessionId
            ? normalizeSession({ ...session, ...updatedSession })
            : session,
        ),
      );
      setRenameSessionTarget(null);
      setRenameTitle('');
    } catch (error) {
      console.error('重命名会话失败:', error);
    }
  };

  const handleTogglePinSession = async (session: Session, event: React.MouseEvent) => {
    event.stopPropagation();
    setOpenSessionMenu(null);
    try {
      const updatedSession = await chatService.setSessionPinned(session.sessionId, !session.pinned);
      setSessions((prev) =>
        prev
          .map((item) =>
            item.sessionId === session.sessionId
              ? normalizeSession({ ...item, ...updatedSession })
              : item,
          )
          .sort((a, b) => {
            if (!!a.pinned !== !!b.pinned) return a.pinned ? -1 : 1;
            return new Date(b.lastActive).getTime() - new Date(a.lastActive).getTime();
          }),
      );
    } catch (error) {
      console.error('更新置顶状态失败:', error);
    }
  };

  const openMindmapInWorkbench = useCallback((mindmap: MindmapData, sourceId?: string) => {
    const tabId = `mindmap-${sourceId || crypto.randomUUID()}`;

    setWorkbenchTabs((currentTabs) => {
      const existingTab = currentTabs.find((tab) => tab.id === tabId);
      if (existingTab) {
        return currentTabs.map((tab) =>
          tab.id === tabId
            ? { ...tab, mindmap, title: getMindmapTabTitle(mindmap) }
            : tab,
        );
      }

      return [
        ...currentTabs,
        {
          id: tabId,
          type: 'mindmap',
          title: getMindmapTabTitle(mindmap),
          mindmap,
          createdAt: Date.now(),
        },
      ];
    });
    setActiveWorkbenchTabId(tabId);
    setIsWorkbenchOpen(true);
  }, []);

  const openResumeInWorkbench = useCallback((resume: ResumeVO, sourceId?: string, optimizeResult?: OptimizeResult) => {
    const tabId = `resume-${sourceId || crypto.randomUUID()}`;

    setWorkbenchTabs((currentTabs) => {
      const existingTab = currentTabs.find((tab) => tab.id === tabId);
      if (existingTab) {
        return currentTabs.map((tab) =>
          tab.id === tabId
              ? {
                  ...tab,
                  type: 'resume',
                  resume,
                  optimizeResult: optimizeResult || (tab.type === 'resume' ? tab.optimizeResult : undefined),
                  title: getResumeTabTitle(resume),
                }
            : tab,
        );
      }

      return [
        ...currentTabs,
        {
          id: tabId,
          type: 'resume',
          title: getResumeTabTitle(resume),
          resume,
          optimizeResult,
          createdAt: Date.now(),
        },
      ];
    });
    setActiveWorkbenchTabId(tabId);
    setIsWorkbenchOpen(true);
  }, []);

  const openOptimizeResultInWorkbench = useCallback((result: OptimizeResult, sourceId?: string) => {
    const resume = result.optimizedResume || result.resume;
    if (resume) {
      openResumeInWorkbench(resume, sourceId, result);
      return;
    }

    const tabId = `optimize-${sourceId || crypto.randomUUID()}`;

    setWorkbenchTabs((currentTabs) => {
      const existingTab = currentTabs.find((tab) => tab.id === tabId);
      if (existingTab) {
        return currentTabs.map((tab) =>
          tab.id === tabId
            ? { ...tab, type: 'optimize_result', result, title: getOptimizeTabTitle(result) }
            : tab,
        );
      }

      return [
        ...currentTabs,
        {
          id: tabId,
          type: 'optimize_result',
          title: getOptimizeTabTitle(result),
          result,
          createdAt: Date.now(),
        },
      ];
    });
    setActiveWorkbenchTabId(tabId);
    setIsWorkbenchOpen(true);
  }, [openResumeInWorkbench]);

  const updateResumeWorkbenchTab = useCallback((tabId: string, resume: ResumeVO) => {
    setWorkbenchTabs((currentTabs) =>
      currentTabs.map((tab) =>
        tab.id === tabId && tab.type === 'resume'
          ? { ...tab, resume, title: getResumeTabTitle(resume) }
          : tab,
      ),
    );
  }, []);

  const openArtifactsInWorkbench = useCallback((artifacts: StructuredArtifacts | null, sourceId?: string) => {
    if (!artifacts) return;
    if (artifacts.resume) openResumeInWorkbench(artifacts.resume, sourceId, artifacts.optimizeResult);
    else if (artifacts.optimizeResult) openOptimizeResultInWorkbench(artifacts.optimizeResult, sourceId);
  }, [openOptimizeResultInWorkbench, openResumeInWorkbench]);

  const handleCloseWorkbenchTab = useCallback((tabId: string, event?: React.MouseEvent) => {
    event?.stopPropagation();
    setWorkbenchTabs((currentTabs) => {
      const nextTabs = currentTabs.filter((tab) => tab.id !== tabId);

      setActiveWorkbenchTabId((currentActiveId) => {
        if (currentActiveId !== tabId) return currentActiveId;
        const closedTabIndex = currentTabs.findIndex((tab) => tab.id === tabId);
        return nextTabs[Math.max(0, closedTabIndex - 1)]?.id || nextTabs[0]?.id || null;
      });

      return nextTabs;
    });
  }, []);

  const sendChatMessage = useCallback((
    rawContent: string,
    options?: {
      forcePost?: boolean;
      optimizeTabId?: string;
      displayContent?: string;
      displayMessage?: ChatMessage;
      quotedUris?: string[];
    },
  ) => {
    const baseContent = rawContent.trim();
    const normalizedQuotedUris = normalizeQuotedResourceUris(options?.quotedUris || quotedResourceUris);
    const content = buildReferencedUserMessage(baseContent, normalizedQuotedUris);
    const displayContent = buildReferencedDisplayContent(
      options?.displayContent || baseContent,
      normalizedQuotedUris,
    );
    if (!content || isLoading) return;
    const targetSessionId = sessionId;
    if (!targetSessionId) return;

    if (!currentUser) {
      setIsSettingsOpen(true);
      setOptimizingWorkbenchTabId(null);
      return;
    }

    const currentFileId = attachedFile?.fileId;

    const userMessage: ChatMessage = options?.displayMessage || {
      id: crypto.randomUUID(),
      role: 'user',
      content: displayContent,
      timestamp: new Date(),
    };

    setSessionMessages(targetSessionId, (prev) => [...prev, userMessage]);
    if (!options?.forcePost) {
      setInput('');
      setAttachedFile(null);
      setQuotedResourceUris([]);
    }
    setSessionLoading(targetSessionId, true);
    setIsChatMode(true);
    startMainAgentState();

    const aiMessageId = crypto.randomUUID();
    const aiMessagePlaceholder: ChatMessage = {
      id: aiMessageId,
      role: 'assistant',
      content: '',
      timestamp: new Date(),
      isStreaming: true,
    };
    setSessionMessages(targetSessionId, (prev) => [...prev, aiMessagePlaceholder]);
    setStreamingMessageId(aiMessageId);

    const handleAssistantDone = (
      messageContent: string,
      extra?: { artifacts?: ArtifactEnvelope[]; mindmapData?: unknown; questionnaireData?: unknown },
    ) => {
      const isTargetVisible = isVisibleSession(targetSessionId);
      const envelopeArtifacts = parseArtifactEnvelopes(extra?.artifacts);
      const mindmap = parseMindmapArtifactEnvelopes(extra?.artifacts) || parseMindmapData(extra?.mindmapData);
      const questionnaire = parseQuestionnaireData(extra?.questionnaireData);
      const artifacts = mergeArtifacts(
        envelopeArtifacts,
        questionnaire ? { questionnaire } : null,
      );
      const displayContent = getArtifactAwareDisplayContent(messageContent, artifacts, mindmap);

      if (isTargetVisible) {
        setSessionMessages(targetSessionId, (prev) =>
          prev.map((message) =>
            message.id === aiMessageId
              ? artifacts?.questionnaire
                ? {
                    ...createQuestionTraceMessage(
                      artifacts.questionnaire.questions,
                      undefined,
                      aiMessageId,
                      artifacts.questionnaire,
                    ),
                    content: displayContent,
                    isStreaming: false,
                  }
                : {
                    ...message,
                    content: displayContent,
                    isStreaming: false,
                    mindmap: mindmap || undefined,
                    resumeData: artifacts?.resume,
                    optimizeResult: artifacts?.optimizeResult,
                  }
                : message,
          ),
        );
      }
      if (isTargetVisible && mindmap) openMindmapInWorkbench(mindmap, aiMessageId);
      if (isTargetVisible && artifacts?.questionnaire) {
        setPendingQuestions(artifacts.questionnaire.questions);
        setPendingQuestion(artifacts.questionnaire.questions[0] || null);
        setPendingId('');
        setActiveQuestionnaire(artifacts.questionnaire);
      }
      if (isTargetVisible && options?.optimizeTabId && (artifacts?.optimizeResult || artifacts?.resume)) {
        setWorkbenchTabs((currentTabs) =>
          currentTabs.map((tab) =>
            tab.id === options.optimizeTabId && tab.type === 'resume'
              ? {
                  ...tab,
                  optimizeResult: artifacts.optimizeResult || tab.optimizeResult,
                  resume: artifacts.resume ||
                    artifacts.optimizeResult?.optimizedResume ||
                    artifacts.optimizeResult?.resume ||
                    tab.resume,
                }
              : tab,
          ),
        );
        setActiveWorkbenchTabId(options.optimizeTabId);
        setIsWorkbenchOpen(true);
      } else if (isTargetVisible) {
        openArtifactsInWorkbench(artifacts, aiMessageId);
      }
      if (isTargetVisible) {
        setStreamingMessageId(null);
        setOptimizingWorkbenchTabId(null);
      }
      finishMainAgentState('success', '本轮已完成');
    };

    const handleRequestError = (message: string) => {
      if (isVisibleSession(targetSessionId)) {
        setSessionMessages(targetSessionId, (prev) =>
          prev.map((item) =>
            item.id === aiMessageId
              ? { ...item, content: message, isStreaming: false }
              : item,
          ),
        );
        setStreamingMessageId(null);
        setOptimizingWorkbenchTabId(null);
      }
      setSessionLoading(targetSessionId, false);
      finishMainAgentState('failed', message);
    };

    if (options?.forcePost) {
      chatService.sendMessage({
        sessionId: targetSessionId,
        userMessage: content,
        userId: currentUser.username,
        username: currentUser.username,
        language: 'zh-CN',
        outputStyle: 'concise',
        fileId: currentFileId,
      })
        .then((response) => {
          if (isVisibleSession(targetSessionId)) {
            updateTaskState(response.taskPlan, response.taskProgress);
          }

          handleAssistantDone(response.aiMessage || '', {
            artifacts: response.artifacts,
            mindmapData: response.mindmapData,
            questionnaireData: response.questionnaireData,
          });
        })
        .catch((error) => {
          const message = error instanceof Error ? error.message : '未知错误';
          handleRequestError(`发生错误：${message}`);
        })
        .finally(() => {
          setSessionLoading(targetSessionId, false);
          if (isVisibleSession(targetSessionId)) {
            setStreamingMessageId(null);
            setOptimizingWorkbenchTabId(null);
          }
          loadSessions();
        });
      return;
    }

    sendStreamMessage(targetSessionId, content, {
      onSessionStarted: (payload: SessionStartedPayload) => {
        if (!isVisibleSession(targetSessionId)) return;
        resetRunProcess(payload.runId);
        markMainAgentConnected(payload.runId);
      },

      onMessageDelta: (_delta, fullText) => {
        if (!isVisibleSession(targetSessionId)) return;
        const streamingDisplayContent = getStreamingStructuredDisplayContent(fullText);
        setSessionMessages(targetSessionId, (prev) =>
          prev.map((message) =>
            message.id === aiMessageId
              ? {
                  ...message,
                  content: streamingDisplayContent || fullText,
                }
              : message,
          ),
        );
      },

      onMessageDone: (messageContent, payload) => {
        handleAssistantDone(messageContent, payload);
      },

      onAssistantCheckpoint: (payload: AssistantCheckpointPayload) => {
        if (!isVisibleSession(targetSessionId)) return;
        upsertAssistantCheckpoint(targetSessionId, aiMessageId, payload);
      },

      onRunStep: (payload: RunStepPayload) => {
        if (!isVisibleSession(targetSessionId)) return;
        upsertRunStep(payload);
        if (payload.agentScope === 'main') {
          updateMainAgentRunning(payload);
        }
      },

      onToolAction: (payload: ToolUseActionPayload) => {
        if (!isVisibleSession(targetSessionId)) return;
        upsertAssistantAction(targetSessionId, aiMessageId, payload);
      },

      onArtifactReady: (payload: ArtifactReadyPayload) => {
        if (!isVisibleSession(targetSessionId)) return;
        upsertArtifactReady(targetSessionId, aiMessageId, payload);
      },

      onDelegationAction: (payload: DelegationActionPayload) => {
        if (!isVisibleSession(targetSessionId)) return;
        upsertDelegationAction(targetSessionId, aiMessageId, payload);
      },

      onTaskUpdate: (payload) => {
        if (!isVisibleSession(targetSessionId)) return;
        updateTaskState(payload.taskPlan, payload.taskProgress);
      },

      onDone: (payload) => {
        if (isVisibleSession(targetSessionId)) {
          updateTaskState(payload.taskPlan, payload.taskProgress);
          setStreamingMessageId(null);
          setOptimizingWorkbenchTabId(null);
        }
        setSessionLoading(targetSessionId, false);
        finishMainAgentState('success', '本轮已完成');
        loadSessions();
      },

      onError: (payload) => {
        handleRequestError(`发生错误：${payload.message}`);
      },

      onConnectionError: (error) => {
        handleRequestError(`连接中断：${error.message}`);
      },
    }, currentFileId, currentUser.username, currentUser.username, 'zh-CN', 'concise');
  }, [
    attachedFile,
    currentUser,
    isVisibleSession,
    isLoading,
    loadSessions,
    openArtifactsInWorkbench,
    openMindmapInWorkbench,
    quotedResourceUris,
    finishMainAgentState,
    markMainAgentConnected,
    resetRunProcess,
    resetMainAgentState,
    sendStreamMessage,
    setSessionLoading,
    sessionId,
    startMainAgentState,
    upsertArtifactReady,
    upsertAssistantAction,
    upsertAssistantCheckpoint,
    upsertDelegationAction,
    upsertRunStep,
    updateMainAgentRunning,
    updateTaskState,
  ]);

  const handleQuestionAnswer = useCallback((answers: UserAnswer[]) => {
    const answeredQuestions = pendingQuestions.length ? pendingQuestions : pendingQuestion ? [pendingQuestion] : [];
    const questionnaire = activeQuestionnaire || undefined;
    const answerTraceMessage = createQuestionnaireAnswerTraceMessage(answeredQuestions, answers);
    const answerPrompt = formatQuestionnaireAnswerPrompt(answeredQuestions, answers, questionnaire);

    setIsQuestionDialogOpen(false);
    setPendingQuestion(null);
    setPendingQuestions([]);
    setPendingId('');
    setActiveQuestionnaire(null);

    sendChatMessage(answerPrompt, {
      forcePost: true,
      displayContent: answerTraceMessage.content,
      displayMessage: answerTraceMessage,
    });
  }, [activeQuestionnaire, pendingQuestion, pendingQuestions, sendChatMessage]);

  const handleSend = useCallback(async () => {
    if ((!input.trim() && quotedResourceUris.length === 0) || isLoading) return;
    sendChatMessage(input, { quotedUris: quotedResourceUris });
  }, [input, isLoading, quotedResourceUris, sendChatMessage]);

  const handleResumeOptimizeRequest = useCallback((tabId: string, resume: ResumeVO, request: ResumeOptimizeRequest) => {
    setOptimizingWorkbenchTabId(tabId);
    const displayContent = [
      '请根据当前工作台简历进行 AI 优化。',
      `目标岗位：${request.targetPosition || resume.jobIntention?.position || resume.basicInfo?.position || '未指定'}`,
      `优化范围：${request.scope || 'full'}`,
      request.jobDescription ? '已附带 JD / 岗位要求。' : '未附带 JD，请做通用优化。',
    ].join('\n');
    sendChatMessage(buildResumeOptimizePrompt(resume, request), {
      forcePost: true,
      optimizeTabId: tabId,
      displayContent,
    });
  }, [sendChatMessage]);

  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      handleSend();
    }
  };

  const handleInputChange = (event: React.ChangeEvent<HTMLTextAreaElement>) => {
    const textarea = event.currentTarget;
    setInput(textarea.value);
    requestAnimationFrame(() => resizeTextarea(textarea));
  };

  const handleFileUpload = useCallback(async (file: File) => {
    const allowedTypes = ['pdf', 'doc', 'docx', 'txt', 'html', 'htm'];
    const ext = file.name.split('.').pop()?.toLowerCase() || '';

    if (!allowedTypes.includes(ext)) {
      alert(`不支持的文件类型：.${ext}\n支持的类型：PDF、Word、TXT、HTML`);
      return;
    }

    if (file.size > 15 * 1024 * 1024) {
      alert('文件大小超过限制（最大 15MB）');
      return;
    }

    setIsUploadingFile(true);
    try {
      const response = await fileService.upload(file);
      if (response.success) {
        setAttachedFile(response);
      } else {
        alert(response.errorMessage || '文件上传失败');
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : '文件上传失败';
      alert(message);
    } finally {
      setIsUploadingFile(false);
    }
  }, []);

  const handleFileInputChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (file) {
      handleFileUpload(file);
    }
    event.target.value = '';
  }, [handleFileUpload]);

  const handleDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.stopPropagation();
    setIsDraggingFile(true);
  }, []);

  const handleDragLeave = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.stopPropagation();
    setIsDraggingFile(false);
  }, []);

  const handleDrop = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.stopPropagation();
    setIsDraggingFile(false);

    const file = event.dataTransfer.files[0];
    if (file) {
      handleFileUpload(file);
    }
  }, [handleFileUpload]);

  const handleRemoveFile = useCallback(() => {
    setAttachedFile(null);
  }, []);

  const handleSkillUpload = useCallback(async (file: File) => {
    const allowedTypes = ['skill', 'zip'];
    const ext = file.name.split('.').pop()?.toLowerCase() || '';
    setSkillUploadFileName(file.name);

    if (!allowedTypes.includes(ext)) {
      setSkillUploadError(`不支持的文件类型：.${ext || 'unknown'}，请上传 .skill 或 .zip 文件`);
      setUploadedSkill(null);
      return;
    }

    setIsUploadingSkill(true);
    setSkillUploadError(null);
    try {
      const response = await skillService.upload(file);
      setUploadedSkill(response);
      setSkillInstructionDraft('');
      await loadExistingSkills();
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Skill 上传失败';
      setSkillUploadError(message);
      setUploadedSkill(null);
    } finally {
      setIsUploadingSkill(false);
    }
  }, [loadExistingSkills]);

  const handleSkillFileInputChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (file) {
      handleSkillUpload(file);
    }
    event.target.value = '';
  }, [handleSkillUpload]);

  const handleSkillDragOver = useCallback((event: React.DragEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.stopPropagation();
    setIsDraggingSkillFile(true);
  }, []);

  const handleSkillDragLeave = useCallback((event: React.DragEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.stopPropagation();
    setIsDraggingSkillFile(false);
  }, []);

  const handleSkillDrop = useCallback((event: React.DragEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.stopPropagation();
    setIsDraggingSkillFile(false);

    const file = event.dataTransfer.files[0];
    if (file) {
      handleSkillUpload(file);
    }
  }, [handleSkillUpload]);

  const handleKnowledgeBaseFilesSelect = useCallback((files: FileList | null) => {
    if (!files || files.length === 0) return;
    const nextFiles = Array.from(files);
    setKnowledgeBaseFiles((currentFiles) => {
      const merged = [...currentFiles];
      for (const file of nextFiles) {
        const exists = merged.some(
          (currentFile) =>
            currentFile.name === file.name &&
            currentFile.size === file.size &&
            currentFile.lastModified === file.lastModified,
        );
        if (!exists) {
          merged.push(file);
        }
      }
      return merged;
    });
    setKnowledgeBaseUploadSummary(null);
    setKnowledgeBaseFeedback(null);
  }, []);

  const handleKnowledgeBaseFileInputChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    handleKnowledgeBaseFilesSelect(event.target.files);
    event.target.value = '';
  }, [handleKnowledgeBaseFilesSelect]);

  const handleKnowledgeBaseDragOver = useCallback((event: React.DragEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.stopPropagation();
    setIsDraggingKnowledgeBaseFiles(true);
  }, []);

  const handleKnowledgeBaseDragLeave = useCallback((event: React.DragEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.stopPropagation();
    setIsDraggingKnowledgeBaseFiles(false);
  }, []);

  const handleKnowledgeBaseDrop = useCallback((event: React.DragEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.stopPropagation();
    setIsDraggingKnowledgeBaseFiles(false);
    handleKnowledgeBaseFilesSelect(event.dataTransfer.files);
  }, [handleKnowledgeBaseFilesSelect]);

  const handleRemoveKnowledgeBaseFile = useCallback((targetFile: File) => {
    setKnowledgeBaseFiles((currentFiles) =>
      currentFiles.filter(
        (file) =>
          !(
            file.name === targetFile.name &&
            file.size === targetFile.size &&
            file.lastModified === targetFile.lastModified
          ),
      ),
    );
    setKnowledgeBaseUploadSummary(null);
  }, []);

  const handleClearKnowledgeBase = useCallback(() => {
    setKnowledgeBaseFiles([]);
    setKnowledgeBaseSourceUrl('');
    setKnowledgeBaseTextFileName('');
    setKnowledgeBaseText('');
    setKnowledgeBaseUploadSummary(null);
    setKnowledgeBaseFeedback(null);
    if (knowledgeBaseFileInputRef.current) {
      knowledgeBaseFileInputRef.current.value = '';
    }
  }, []);

  const handleConfirmKnowledgeBase = useCallback(() => {
    const hasFiles = knowledgeBaseFiles.length > 0;
    const hasUrl = knowledgeBaseSourceUrl.trim().length > 0;
    const hasText = knowledgeBaseText.trim().length > 0;
    const normalizedTextFileName = knowledgeBaseTextFileName.trim().replace(/\.md$/i, '');

    if (!hasFiles && !hasUrl && !hasText) {
      setKnowledgeBaseFeedback('请先添加文件、仓库链接或文本内容。');
      return;
    }

    if (hasText && !normalizedTextFileName) {
      setKnowledgeBaseFeedback('知识库中的纯文本内容需要先填写文件名。');
      return;
    }

    const sourceSummary = [
      hasFiles ? `${knowledgeBaseFiles.length} 个文件` : null,
      hasUrl ? '1 个链接来源' : null,
      hasText ? `纯文本资源 ${normalizedTextFileName}.md` : null,
    ].filter(Boolean).join(' + ');

    if (!hasFiles) {
      setKnowledgeBaseUploadSummary(null);
    }

    // 知识库：调用后端 API
    const runAddResources = async () => {
      const results: string[] = [];

      // 上传文件
      if (hasFiles) {
        try {
          const uploadResults = await resourceService.uploadFiles(knowledgeBaseFiles);
          const successCount = uploadResults.filter((r) => r.status === 'success').length;
          const conflictCount = uploadResults.filter((r) => r.status === 'conflict').length;
          const failedCount = uploadResults.filter((r) => r.status === 'failed').length;
          results.push(`文件: ${successCount} 成功${conflictCount > 0 ? `, ${conflictCount} 冲突` : ''}${failedCount > 0 ? `, ${failedCount} 失败` : ''}`);
          if (successCount === uploadResults.length && successCount > 0) {
            setKnowledgeBaseFiles([]);
            setKnowledgeBaseUploadSummary(`文件资源已添加：${successCount} 个文件`);
            if (knowledgeBaseFileInputRef.current) {
              knowledgeBaseFileInputRef.current.value = '';
            }
          }
        } catch (error) {
          results.push(`文件: 上传失败 - ${error instanceof Error ? error.message : '未知错误'}`);
        }
      }

      // 导入 URL
      if (hasUrl) {
        try {
          const urlResult = await resourceService.importFromUrl(knowledgeBaseSourceUrl.trim());
          results.push(`URL: ${urlResult.status === 'success' ? '导入请求已提交' : urlResult.message || '失败'}`);
        } catch (error) {
          results.push(`URL: 导入失败 - ${error instanceof Error ? error.message : '未知错误'}`);
        }
      }

      // 创建纯文本资源
      if (hasText && normalizedTextFileName) {
        try {
          const textResult = await resourceService.createTextResource(normalizedTextFileName, knowledgeBaseText);
          results.push(`文本: ${textResult.status === 'success' ? '创建成功' : textResult.message || '失败'}`);
        } catch (error) {
          results.push(`文本: 创建失败 - ${error instanceof Error ? error.message : '未知错误'}`);
        }
      }

      setKnowledgeBaseFeedback(results.length > 0 ? results.join(' | ') : `已添加资源：${sourceSummary}`);

      // 刷新目录
      void loadResourceDirectory(resourceDirectoryPath, 'knowledge-base');
    };

    void runAddResources();
  }, [knowledgeBaseFiles, knowledgeBaseSourceUrl, knowledgeBaseText, knowledgeBaseTextFileName, loadResourceDirectory, resourceDirectoryPath]);

  const openResourceDirectory = useCallback((targetPath: string, variant: ResourceBrowserVariant = resourceBrowserVariant) => {
    const rootPath = variant === 'workspace' ? ROOT_WORKSPACE_PATH : ROOT_RESOURCE_PATH;
    void loadResourceDirectory(normalizeResourceDirectoryPath(targetPath, rootPath), variant);
    setKnowledgeBaseFeedback(null);
  }, [loadResourceDirectory, resourceBrowserVariant]);

  const handleRefreshResourceDirectory = useCallback((variant: ResourceBrowserVariant = resourceBrowserVariant) => {
    const rootPath = variant === 'workspace' ? ROOT_WORKSPACE_PATH : ROOT_RESOURCE_PATH;
    const targetPath = normalizeResourceDirectoryPath(resourceDirectoryInput || resourceDirectoryPath, rootPath);
    void loadResourceDirectory(targetPath, variant);
    setKnowledgeBaseFeedback(`已刷新目录：${targetPath}`);
  }, [loadResourceDirectory, resourceBrowserVariant, resourceDirectoryInput, resourceDirectoryPath]);

  const handleGoBackResourceDirectory = useCallback((variant: ResourceBrowserVariant = resourceBrowserVariant) => {
    const rootPath = variant === 'workspace' ? ROOT_WORKSPACE_PATH : ROOT_RESOURCE_PATH;
    const normalizedCurrentPath = normalizeResourceDirectoryPath(resourceDirectoryPath, rootPath);
    if (normalizedCurrentPath === rootPath) {
      setKnowledgeBaseFeedback('当前已经在资源根目录。');
      return;
    }

    openResourceDirectory(getResourceParentPath(normalizedCurrentPath, rootPath), variant);
  }, [openResourceDirectory, resourceBrowserVariant, resourceDirectoryPath]);

  const handleEnterResourceDirectory = useCallback((variant: ResourceBrowserVariant = resourceBrowserVariant) => {
    const rootPath = variant === 'workspace' ? ROOT_WORKSPACE_PATH : ROOT_RESOURCE_PATH;
    const typedPath = normalizeResourceDirectoryPath(resourceDirectoryInput || resourceDirectoryPath, rootPath);
    if (typedPath !== resourceDirectoryPath) {
      openResourceDirectory(typedPath, variant);
      return;
    }

    if (!isResourceDirectoryLike(selectedResource)) {
      setKnowledgeBaseFeedback('选中目录后才能进入。');
      return;
    }

    openResourceDirectory(normalizeResourceDirectoryPath(selectedResource.uri, rootPath), variant);
  }, [openResourceDirectory, resourceBrowserVariant, resourceDirectoryInput, resourceDirectoryPath, selectedResource]);

  const handleResourceDirectoryInputKeyDown = useCallback((
    event: React.KeyboardEvent<HTMLInputElement>,
    variant: ResourceBrowserVariant = resourceBrowserVariant,
  ) => {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    const rootPath = variant === 'workspace' ? ROOT_WORKSPACE_PATH : ROOT_RESOURCE_PATH;
    const targetPath = normalizeResourceDirectoryPath(resourceDirectoryInput, rootPath);
    setResourceDirectoryInput(targetPath);
    openResourceDirectory(targetPath, variant);
  }, [openResourceDirectory, resourceBrowserVariant, resourceDirectoryInput]);

  const handleDeleteSelectedResource = useCallback(() => {
    const rootUri = getResourcePackageRootUri(selectedResource?.uri);
    if (!rootUri) {
      setKnowledgeBaseFeedback('选中资源后才能删除。');
      return;
    }

    const runDelete = async () => {
      setIsDeletingResource(true);
      setKnowledgeBaseFeedback(`正在删除资源：${rootUri}`);
      try {
        const result = await resourceService.deleteResource(selectedResource?.uri || rootUri);
        if (result.status !== 'success') {
          setKnowledgeBaseFeedback(result.message || `删除失败：${rootUri}`);
          return;
        }

        setSelectedResourceUri('');
        setSelectedResourceDetail(null);
        setKnowledgeBaseFeedback(`已删除资源：${result.rootUri || rootUri}`);
        const refreshPath = resourceDirectoryPath.startsWith(rootUri)
          ? getResourceParentPath(rootUri, ROOT_RESOURCE_PATH)
          : resourceDirectoryPath;
        await loadResourceDirectory(refreshPath, 'knowledge-base');
      } catch (error) {
        setKnowledgeBaseFeedback(`删除失败：${error instanceof Error ? error.message : '未知错误'}`);
      } finally {
        setIsDeletingResource(false);
      }
    };

    void runDelete();
  }, [loadResourceDirectory, resourceDirectoryPath, selectedResource]);

  const handleQuoteResource = useCallback((uri: string) => {
    const normalizedUri = uri.trim();
    if (!normalizedUri) return;

    setQuotedResourceUris((currentUris) => normalizeQuotedResourceUris([...currentUris, normalizedUri]));
    setKnowledgeBaseFeedback(`已引用资源：${normalizedUri}`);
    if (isKnowledgeBaseOpen) {
      setIsKnowledgeBaseOpen(false);
    }
    setIsChatMode(true);
  }, [isKnowledgeBaseOpen]);

  const handleRemoveQuotedResource = useCallback((uri: string) => {
    setQuotedResourceUris((currentUris) => currentUris.filter((currentUri) => currentUri !== uri));
  }, []);

  const handleDeleteExistingSkill = useCallback((skillId: string) => {
    const runDelete = async () => {
      try {
        await skillService.delete(skillId);
        await loadExistingSkills();
      } catch (error) {
        const message = error instanceof Error ? error.message : '删除 skill 失败';
        setExistingSkillsError(message);
      }
    };

    void runDelete();
  }, [loadExistingSkills]);

  const handleChatScroll = useCallback(() => {
    setIsChatScrolling(true);

    if (chatScrollTimerRef.current) {
      window.clearTimeout(chatScrollTimerRef.current);
    }

    chatScrollTimerRef.current = window.setTimeout(() => {
      setIsChatScrolling(false);
    }, 900);
  }, []);

  const handleWorkbenchResizeStart = useCallback((event: React.PointerEvent<HTMLButtonElement>) => {
    event.preventDefault();
    setIsResizingWorkbench(true);
    document.body.classList.add('workbench-resizing');

    const handlePointerMove = (moveEvent: PointerEvent) => {
      const maxWidth = Math.max(
        MIN_WORKBENCH_PANEL_WIDTH,
        window.innerWidth - MIN_MAIN_WITH_WORKBENCH_WIDTH,
      );
      const nextWidth = clampNumber(window.innerWidth - moveEvent.clientX, MIN_WORKBENCH_PANEL_WIDTH, maxWidth);
      setWorkbenchPanelWidth(nextWidth);
    };

    const stopResize = () => {
      setIsResizingWorkbench(false);
      document.body.classList.remove('workbench-resizing');
      window.removeEventListener('pointermove', handlePointerMove);
      window.removeEventListener('pointerup', stopResize);
      window.removeEventListener('pointercancel', stopResize);
    };

    window.addEventListener('pointermove', handlePointerMove);
    window.addEventListener('pointerup', stopResize);
    window.addEventListener('pointercancel', stopResize);
  }, []);

  const quickSuggestions = [
    { icon: <GitBranch size={14} />, text: '帮我生成项目计划思维导图' },
    { icon: <Sparkles size={14} />, text: '解释什么是机器学习' },
    { icon: <MessageSquare size={14} />, text: '写一段产品需求分析' },
    { icon: <Search size={14} />, text: '帮我拆解一个技术方案' },
  ];

  const filteredSessions = sessions.filter((session) =>
    !searchQuery ||
    session.sessionId.toLowerCase().includes(searchQuery.toLowerCase()) ||
    getSessionTitle(session).toLowerCase().includes(searchQuery.toLowerCase()),
  );

  const isSendDisabled = (!input.trim() && quotedResourceUris.length === 0) || isLoading || !authChecked;
  const isComposerInputDisabled = !authChecked || !currentUser;
  const currentTaskProgress = getTaskProgress(taskPlan, taskProgress);
  const completedTasks = currentTaskProgress.completed + currentTaskProgress.skipped;
  const hasTasks = taskPlan.length > 0;
  const subAgentSummaries: SubAgentSummary[] = runProcess
    ? Object.values(runProcess.stepMap)
      .filter((node) => node.kind === 'sub_agent')
      .sort((a, b) => a.firstSeenAt - b.firstSeenAt)
      .map((node) => ({
        id: node.id,
        label: node.agentLabel || node.title || node.name || '子代理',
        status: node.status,
      }))
    : [];
  const mainAgentElapsed = mainAgentState.startedAt
    ? formatElapsedTime((mainAgentState.endedAt ?? runProcessClock) - mainAgentState.startedAt)
    : '0:00';
  const mainAgentStatusLabel = mainAgentState.phase === 'connected'
    ? '已连接'
    : mainAgentState.phase === 'running'
      ? '运行中'
      : mainAgentState.phase === 'success'
        ? '已完成'
        : mainAgentState.phase === 'failed'
          ? '失败'
          : mainAgentState.phase === 'pending'
            ? '等待中'
            : '连接中';
  const activeWorkbenchTab = workbenchTabs.find((tab) => tab.id === activeWorkbenchTabId) || workbenchTabs[0] || null;
  const hasWorkbenchTabs = workbenchTabs.length > 0;
  const isWorkbenchVisible = isWorkbenchOpen;

  const renderInlineRunSteps = (steps?: RunStepPayload[]) => {
    const visibleSteps = (steps || []).filter((step) => step.kind !== 'llm');
    if (visibleSteps.length === 0) return null;

    return (
      <div className="inline-run-steps" aria-label="工具执行过程">
        <div className="inline-run-steps-header">
          <span>批量工具</span>
          <b>{visibleSteps.length}</b>
        </div>
        <div className="inline-run-step-list">
          {visibleSteps.map((step, index) => {
            const statusIcon = step.status === 'success'
              ? <Check size={13} />
              : step.status === 'failed'
                ? <X size={13} />
                : step.status === 'blocked'
                  ? <Clock size={13} />
                  : step.status === 'pending'
                    ? <Circle size={10} fill="currentColor" />
                    : <LoaderCircle size={13} className="spin" />;
            const kindIcon = step.kind === 'sub_agent'
              ? <Bot size={14} />
              : step.kind === 'tool_batch'
                ? <ListChecks size={14} />
                : <Globe size={14} />;

            return (
              <div key={step.id} className={`inline-run-step status-${step.status}`}>
                <span className="inline-run-step-icon">{kindIcon}</span>
                <div className="inline-run-step-copy">
                  <strong>{getRunStepBrief(step)}</strong>
                  <small>{getRunStepKindLabel(step.kind)}</small>
                </div>
                <span className="inline-run-step-status" title={getRunStepStatusLabel(step.status)}>
                  {statusIcon}
                </span>
                <span className="inline-run-step-index">{index + 1}/{visibleSteps.length}</span>
              </div>
            );
          })}
        </div>
      </div>
    );
  };

  const renderAssistantActions = (message: ChatMessage) => {
    const actions = message.actions;
    const timeline = buildAssistantTimeline(actions);
    if (!timeline.length) return null;

    const renderStatusIcon = (status?: string) => status === 'success'
      ? <Check size={13} />
      : status === 'failed'
        ? <X size={13} />
        : status === 'blocked' || status === 'pending'
          ? <Clock size={13} />
          : <LoaderCircle size={13} className="spin" />;

    const renderToolIcon = (toolName?: string, size = 14) => (
      toolName?.includes('grep') || toolName?.includes('find') || toolName?.includes('search')
        ? <Search size={size} />
        : toolName?.includes('read')
          ? <FileText size={size} />
          : <Database size={size} />
    );

    const renderActionResources = (
      action: Pick<ToolUseActionPayload, 'resourceUris' | 'preview' | 'description' | 'summary'>,
      limit = 3,
    ) => {
      const uris = getActionResourceUris(action);
      if (!uris.length) return null;

      const visibleUris = uris.slice(0, limit);
      const hiddenCount = uris.length - visibleUris.length;

      return (
        <div className="assistant-action-resources" aria-label="读取路径">
          {visibleUris.map((uri) => (
            <span key={uri} className="assistant-action-resource-chip" title={uri}>
              {uri}
            </span>
          ))}
          {hiddenCount > 0 && (
            <span className="assistant-action-resource-more" title={uris.slice(limit).join('\n')}>
              +{hiddenCount}
            </span>
          )}
        </div>
      );
    };

    const renderGroupedActionResources = (items: Extract<AssistantActionItem, { kind: 'tool_use' }>[]) => {
      const uris = normalizeQuotedResourceUris(items.flatMap((item) => getActionResourceUris(item)));
      return renderActionResources({ resourceUris: uris }, 3);
    };

    const renderDelegationChild = (child: AssistantTimelineAction) => {
      if (child.kind === 'checkpoint') {
        return (
          <div key={child.id} className={`assistant-delegation-child checkpoint phase-${child.phase || 'info'}`}>
            <span className="assistant-action-icon">
              <Sparkles size={12} />
            </span>
            <div className="assistant-action-copy">
              <strong>{child.title || '子 Agent 更新'}</strong>
              {child.content && <small>{child.content}</small>}
            </div>
          </div>
        );
      }

      if (child.kind === 'artifact_ready') {
        return (
          <button
            key={child.id}
            type="button"
            className="assistant-delegation-child artifact-ready"
            onClick={() => openArtifactsInWorkbench(getMessageArtifacts(message), message.id)}
          >
            <span className="assistant-action-icon">
              {child.artifactType === 'mindmap' ? <GitBranch size={12} /> : <FileText size={12} />}
            </span>
            <div className="assistant-action-copy">
              <strong>{child.title || '产物已生成'}</strong>
              {child.summary && <small>{child.summary}</small>}
            </div>
            <span className="assistant-action-status" title="完成">
              <Check size={12} />
            </span>
          </button>
        );
      }

      if (child.kind === 'user_question') {
        return (
          <button
            key={child.id}
            type="button"
            className="assistant-delegation-child user-question"
            onClick={() => reopenQuestionDialog(message.questionTrace)}
          >
            <span className="assistant-action-icon">
              <MessageSquare size={12} />
            </span>
            <div className="assistant-action-copy">
              <strong>{child.title}</strong>
              {child.summary && <small>{child.summary}</small>}
            </div>
            <span className="assistant-action-count">{child.questionCount}</span>
          </button>
        );
      }

      const detail = child.status === 'failed'
        ? child.error || '工具执行失败'
        : child.summary || child.description || '';

      return (
        <div key={child.id} className={`assistant-delegation-child status-${child.status}`}>
          <span className="assistant-action-icon">
            {renderToolIcon(child.toolName, 12)}
          </span>
          <div className="assistant-action-copy">
            <strong>{child.title || child.toolName}</strong>
            {detail && <small>{detail}</small>}
            {renderActionResources(child, 2)}
          </div>
          <span className="assistant-action-status" title={getRunStepStatusLabel(child.status)}>
            {renderStatusIcon(child.status)}
          </span>
        </div>
      );
    };

    return (
      <div className="assistant-actions" aria-label="执行过程">
        {timeline.map((action) => {
          if (action.kind === 'checkpoint') {
            return (
              <div key={action.id} className={`assistant-action checkpoint phase-${action.phase || 'info'}`}>
                <span className="assistant-action-icon">
                  <Sparkles size={14} />
                </span>
                <div className="assistant-action-copy">
                  <strong>{action.title || '更新'}</strong>
                  {action.content && <small>{action.content}</small>}
                </div>
              </div>
            );
          }

          if (action.kind === 'tool_group') {
            return (
              <details key={action.id} className={`assistant-action-group status-${action.status}`}>
                <summary>
                  <span className="assistant-action-icon">
                    <Database size={14} />
                  </span>
                  <div className="assistant-action-copy">
                    <strong>{action.title}</strong>
                    <small>{action.summary || `${action.items.length} 个工具调用`}</small>
                    {renderGroupedActionResources(action.items)}
                  </div>
                  <span className="assistant-action-status" title={getRunStepStatusLabel(action.status)}>
                    {renderStatusIcon(action.status)}
                  </span>
                  <span className="assistant-action-count">{action.items.length}</span>
                </summary>
                <div className="assistant-action-group-items">
                  {action.items.map((item) => {
                    const itemDetail = item.status === 'failed'
                      ? item.error || '工具执行失败'
                      : item.summary || item.description || '';
                    return (
                      <div key={item.id} className={`assistant-action group-item status-${item.status}`}>
                        <span className="assistant-action-icon">
                          {renderToolIcon(item.toolName, 13)}
                        </span>
                        <div className="assistant-action-copy">
                          <strong>{item.title || item.toolName}</strong>
                          {itemDetail && <small>{itemDetail}</small>}
                          {renderActionResources(item, 2)}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </details>
            );
          }

          if (action.kind === 'delegation_group') {
            const detail = action.status === 'failed'
              ? action.error || '子 Agent 执行失败'
              : action.summary || action.task || '';
            const turnSummary = action.turnCount != null && action.maxTurns != null
              ? `${action.turnCount}/${action.maxTurns} 轮`
              : action.children.length > 0
                ? `${action.children.length} 个动作`
                : '';

            return (
              <details key={action.id} className={`assistant-delegation status-${action.status}`} open={action.status === 'running'}>
                <summary>
                  <span className="assistant-action-icon">
                    <Bot size={14} />
                  </span>
                  <div className="assistant-action-copy">
                    <strong>{action.title || `委托给 ${action.agentLabel}`}</strong>
                    {detail && <small>{detail}</small>}
                  </div>
                  <span className="assistant-action-status" title={getRunStepStatusLabel(action.status)}>
                    {renderStatusIcon(action.status)}
                  </span>
                  <span className="assistant-action-count">{turnSummary || action.agentType || '子'}</span>
                </summary>
                {action.children.length > 0 && (
                  <div className="assistant-delegation-children">
                    {action.children.map(renderDelegationChild)}
                  </div>
                )}
              </details>
            );
          }

          if (action.kind === 'artifact_ready') {
            return (
              <button
                key={action.id}
                type="button"
                className="assistant-action artifact-ready"
                onClick={() => openArtifactsInWorkbench(getMessageArtifacts(message), message.id)}
              >
                <span className="assistant-action-icon">
                  {action.artifactType === 'mindmap' ? <GitBranch size={14} /> : <FileText size={14} />}
                </span>
                <div className="assistant-action-copy">
                  <strong>{action.title || '产物已生成'}</strong>
                  {action.summary && <small>{action.summary}</small>}
                </div>
                <span className="assistant-action-status" title="完成">
                  <Check size={13} />
                </span>
              </button>
            );
          }

          if (action.kind === 'user_question') {
            return (
              <button
                key={action.id}
                type="button"
                className="assistant-action user-question"
                onClick={() => reopenQuestionDialog(message.questionTrace)}
              >
                <span className="assistant-action-icon">
                  <MessageSquare size={14} />
                </span>
                <div className="assistant-action-copy">
                  <strong>{action.title}</strong>
                  {action.summary && <small>{action.summary}</small>}
                </div>
                <span className="assistant-action-count">{action.questionCount}</span>
              </button>
            );
          }

          const detail = action.status === 'failed'
            ? action.error || '工具执行失败'
            : action.summary || action.description || '';

          return (
            <div key={action.id} className={`assistant-action status-${action.status}`}>
              <span className="assistant-action-icon">
                {renderToolIcon(action.toolName)}
              </span>
              <div className="assistant-action-copy">
                <strong>{action.title || action.toolName}</strong>
                {detail && <small>{detail}</small>}
                {renderActionResources(action)}
              </div>
              <span className="assistant-action-status" title={getRunStepStatusLabel(action.status)}>
                {renderStatusIcon(action.status)}
              </span>
            </div>
          );
        })}
      </div>
    );
  };

  const reopenQuestionDialog = (trace: ChatMessage['questionTrace']) => {
    if (!trace?.questions.length) return;

    setPendingQuestions(trace.questions);
    setPendingQuestion(trace.questions[0] || null);
    if (trace.pendingId) setPendingId(trace.pendingId);
    setActiveQuestionnaire({
      type: 'questionnaire',
      questionnaireId: trace.questionnaireId,
      title: trace.title,
      questions: trace.questions,
    });
    setIsQuestionDialogOpen(true);
  };

  const renderMessageContent = (message: ChatMessage) => {
    const inlineRunSteps = renderInlineRunSteps(message.runSteps);
    const assistantActions = renderAssistantActions(message);

    if (message.questionTrace) {
      return (
        <div className="question-trace-card">
          <div className="question-trace-header">
            <MessageSquare size={15} />
            <span>{message.questionTrace.title || '需要你补充'}</span>
          </div>
          <div className="question-trace-list">
            {message.questionTrace.questions.map((question, index) => (
              <div key={question.questionId || index} className="question-trace-item">
                <div className="question-trace-title-row">
                  <span className="question-trace-type">{getQuestionTypeLabel(question.questionType)}</span>
                  {message.questionTrace && message.questionTrace.questions.length > 1 && (
                    <span className="question-trace-index">{index + 1}/{message.questionTrace.questions.length}</span>
                  )}
                </div>
                <p className="question-trace-text">{question.questionText}</p>
                {question.options?.length ? (
                  <div className="question-trace-options">
                    {question.options.map((option) => (
                      <span key={getQuestionOptionId(option) || getOptionText(option)}>
                        {getOptionText(option)}
                      </span>
                    ))}
                  </div>
                ) : null}
              </div>
            ))}
          </div>
          <button
            type="button"
            className="question-trace-answer-btn"
            onClick={() => reopenQuestionDialog(message.questionTrace)}
          >
            <MessageSquare size={14} />
            <span>作答</span>
          </button>
          {assistantActions}
          {inlineRunSteps}
        </div>
      );
    }

    if (message.answerTrace) {
      const answerItems = message.answerTrace.answers?.length
        ? message.answerTrace.answers
        : [{ questionText: message.answerTrace.questionText, answerText: message.answerTrace.answerText }];

      return (
        <div className="answer-trace-card">
          <span className="answer-trace-label">我的回答</span>
          <div className="answer-trace-list">
            {answerItems.map((answer, index) => (
              <div key={`${answer.questionText || 'answer'}-${index}`} className="answer-trace-item">
                {answer.questionText && (
                  <span className="answer-trace-question">{answer.questionText}</span>
                )}
                <p>{answer.answerText}</p>
              </div>
            ))}
          </div>
          {assistantActions}
          {inlineRunSteps}
        </div>
      );
    }

    return (
      <>
        {assistantActions}
        {message.content && <p className="message-content-text">{message.content}</p>}
        {inlineRunSteps}
      </>
    );
  };

  const renderWorkbenchTabIcon = (tab: WorkbenchTab) => {
    if (tab.type === 'mindmap') return <GitBranch size={15} />;
    if (tab.type === 'resume') return <FileText size={15} />;
    return <Sparkles size={15} />;
  };

  const renderMessageArtifacts = (message: ChatMessage) => {
    const artifacts = getMessageArtifacts(message);
    if (!artifacts?.resume && !artifacts?.optimizeResult) return null;

    return (
      <div className="message-artifact-actions">
        {artifacts.resume && (
          <button
            className="artifact-tag"
            onClick={() => openResumeInWorkbench(artifacts.resume as ResumeVO, message.id, artifacts.optimizeResult)}
          >
            <FileText size={14} />
            打开简历预览
          </button>
        )}
        {!artifacts.resume && artifacts.optimizeResult && (
          <button
            className="artifact-tag"
            onClick={() => openOptimizeResultInWorkbench(artifacts.optimizeResult as OptimizeResult, message.id)}
          >
            <Sparkles size={14} />
            查看优化分析
          </button>
        )}
      </div>
    );
  };

  const renderWorkbenchContent = (tab: WorkbenchTab) => {
    if (tab.type === 'mindmap') {
      return (
        <div className="workbench-content-card">
          <Suspense fallback={<LazySectionFallback label="正在加载思维导图..." />}>
            <MindmapViewer data={tab.mindmap} />
          </Suspense>
        </div>
      );
    }

    if (tab.type === 'resume') {
      return (
        <Suspense fallback={<LazySectionFallback label="正在加载简历工作台..." />}>
          <ResumeWorkbench
            resume={tab.resume}
            optimizeResult={tab.optimizeResult}
            isOptimizing={optimizingWorkbenchTabId === tab.id}
            onResumeChange={(nextResume) => updateResumeWorkbenchTab(tab.id, nextResume)}
            onOptimize={(request) => handleResumeOptimizeRequest(tab.id, tab.resume, request)}
          />
        </Suspense>
      );
    }

    return (
      <div className="workbench-content-card workbench-result-card">
        <Suspense fallback={<LazySectionFallback label="正在加载分析结果..." />}>
          <OptimizeResultCard result={tab.result} />
        </Suspense>
      </div>
    );
  };

  const renderResourceBrowser = (variant: ResourceBrowserVariant) => {
    const isWorkspace = variant === 'workspace';

    return (
      <div className={`knowledge-base-resource-browser ${isWorkspace ? 'workspace-resource-browser' : ''}`}>
        {!isWorkspace && (
          <div className="knowledge-base-card-head">
            <span className="skills-existing-panel-eyebrow">Filesystem</span>
            <h3>资源目录</h3>
          </div>
        )}

        <div className="knowledge-base-resource-toolbar">
          <div className="knowledge-base-resource-toolbar-main">
            <input
              type="text"
              className="knowledge-base-resource-uri"
              value={resourceDirectoryInput}
              onChange={(event) => setResourceDirectoryInput(event.target.value)}
              onBlur={() => {
                if (!resourceDirectoryInput.trim()) {
                  setResourceDirectoryInput(resourceDirectoryPath);
                }
              }}
              onKeyDown={(event) => handleResourceDirectoryInputKeyDown(event, variant)}
              aria-label="资源目录路径"
              title="按 Enter 加载路径"
              spellCheck={false}
            />
          </div>
          <div className="knowledge-base-resource-toolbar-actions">
            <div className="knowledge-base-resource-controls">
              <button
                type="button"
                className="knowledge-base-resource-control"
                onClick={() => handleGoBackResourceDirectory(variant)}
                aria-label="返回上级目录"
                title="返回上级目录"
              >
                <ArrowLeft size={14} />
              </button>
              <button
                type="button"
                className="knowledge-base-resource-control"
                onClick={() => handleRefreshResourceDirectory(variant)}
                aria-label="刷新目录"
                title="刷新目录"
              >
                <RefreshCw size={14} />
              </button>
              {!isWorkspace && (
                <button
                  type="button"
                  className="knowledge-base-resource-control danger"
                  onClick={handleDeleteSelectedResource}
                  aria-label="删除选中资源"
                  title="删除选中资源"
                  disabled={!selectedResource || isDeletingResource}
                >
                  {isDeletingResource ? <LoaderCircle size={14} className="spin" /> : <Trash2 size={14} />}
                </button>
              )}
              <button
                type="button"
                className="knowledge-base-resource-control primary"
                onClick={() => handleEnterResourceDirectory(variant)}
                aria-label="进入路径或选中目录"
                title="进入路径或选中目录"
              >
                <CornerDownRight size={14} />
              </button>
            </div>
            <span className="knowledge-base-resource-count">{resourceItems.length} 个条目</span>
          </div>
        </div>

        <div className="knowledge-base-resource-grid">
          <div className="knowledge-base-resource-table">
            <div className="knowledge-base-resource-table-head">
              <span>URI</span>
              <span>大小</span>
              <span>类型</span>
              <span>更新时间</span>
              <span>引用</span>
            </div>
            <div className="knowledge-base-resource-table-body">
              {isResourceLoading ? (
                <div className="skills-existing-empty">
                  <LoaderCircle size={18} className="animate-spin" />
                  <p>加载资源目录...</p>
                </div>
              ) : resourceError ? (
                <div className="skills-existing-empty knowledge-base-resource-empty">
                  <X size={18} />
                  <p>{resourceError}</p>
                </div>
              ) : resourceItems.length === 0 ? (
                <div className="skills-existing-empty">
                  <Database size={18} />
                  <p>当前目录为空</p>
                </div>
              ) : (
                resourceItems.map((item) => (
                  <button
                    type="button"
                    key={item.uri}
                    className={`knowledge-base-resource-row ${selectedResource?.uri === item.uri ? 'active' : ''}`}
                    onClick={() => setSelectedResourceUri(item.uri)}
                    onDoubleClick={() => {
                      setSelectedResourceUri(item.uri);
                      if (isResourceDirectoryLike(item)) {
                        openResourceDirectory(item.uri, variant);
                      }
                    }}
                  >
                    <span className="resource-uri" title={item.uri}>{item.uri}</span>
                    <span>{item.size ? formatResourceSize(item.size) : '-'}</span>
                    <span>{isResourceDirectoryLike(item) ? '目录' : item.type || '文件'}</span>
                    <span>{item.updatedAt || '-'}</span>
                    <span className="knowledge-base-resource-row-action">
                      <button
                        type="button"
                        className="knowledge-base-resource-quote"
                        onClick={(event) => {
                          event.stopPropagation();
                          handleQuoteResource(item.uri);
                        }}
                        aria-label={`引用资源 ${item.uri}`}
                        title="引用到聊天输入框"
                      >
                        <span>引用</span>
                      </button>
                    </span>
                  </button>
                ))
              )}
            </div>
          </div>

          <div className="knowledge-base-resource-detail">
            {isResourceDetailLoading ? (
              <div className="skills-existing-empty detail">
                <LoaderCircle size={18} className="animate-spin" />
                <p>加载资源详情...</p>
              </div>
            ) : selectedResourceDetail ? (
              <>
                <div className="knowledge-base-resource-detail-head">
                  <strong>{selectedResourceDetail.name || selectedResourceDetail.uri}</strong>
                  <div className="knowledge-base-resource-detail-topline">
                    <span>{selectedResourceDetail.directory ? '目录概览' : '文件概览'}</span>
                    <div className="knowledge-base-resource-mini-meta">
                      <label>大小</label>
                      <strong>{selectedResourceDetail.size ? formatResourceSize(selectedResourceDetail.size) : '-'}</strong>
                    </div>
                    <div className="knowledge-base-resource-mini-meta">
                      <label>类型</label>
                      <strong>{selectedResourceDetail.directory ? 'Directory' : selectedResourceDetail.type || 'File'}</strong>
                    </div>
                    <div className="knowledge-base-resource-mini-meta">
                      <label>修改时间</label>
                      <strong>{selectedResourceDetail.updatedAt || '-'}</strong>
                    </div>
                  </div>
                </div>
                <div className="knowledge-base-resource-preview-panel">
                  <div className="knowledge-base-resource-preview-head">
                    <span>Preview</span>
                  </div>
                  {selectedResourceDetail.preview ? (
                    <pre className="knowledge-base-resource-preview-content">
                      {selectedResourceDetail.preview}
                    </pre>
                  ) : selectedResourceDetail.abstractText || selectedResourceDetail.overviewText ? (
                    <div className="knowledge-base-resource-preview-empty">
                      <p>{selectedResourceDetail.overviewText || selectedResourceDetail.abstractText}</p>
                    </div>
                  ) : (
                    <div className="knowledge-base-resource-preview-empty">
                      <span>{selectedResourceDetail.directory ? '目录暂无概览内容。' : '当前文件暂无预览。'}</span>
                    </div>
                  )}
                </div>
              </>
            ) : (
              <div className="skills-existing-empty detail knowledge-base-resource-empty">
                <Database size={18} />
                <p>选中一个资源条目后，这里展示目录概览或文件摘要。</p>
              </div>
            )}
          </div>
        </div>
      </div>
    );
  };

  return (
    <div
      className={`app-container ${isWorkbenchVisible ? 'workbench-open' : ''}`}
      style={{
        '--workbench-panel-width': `${workbenchPanelWidth}px`,
        '--app-wallpaper': `url("${wallpaperUrl || DEFAULT_WALLPAPER_URL}")`,
      } as React.CSSProperties}
    >
      <div className="app-background" />

      <aside className={`sidebar ${isSidebarOpen ? 'open' : ''}`}>
        <div className="sidebar-content">
          <div className="sidebar-header">
            <button className="new-chat-btn" onClick={handleNewChat}>
              <Plus size={18} />
              <span>New chat</span>
            </button>
            <button
              className="sidebar-collapse-btn"
              onClick={() => setIsSidebarOpen(!isSidebarOpen)}
              title="收起侧边栏"
            >
              <PanelLeftClose size={18} />
            </button>
          </div>

          <div className="sidebar-search">
            <Search size={16} />
            <input
              type="text"
              placeholder="Search"
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              className="search-input"
            />
          </div>

          <nav className="sidebar-nav">
            <button
              className={`nav-item skills-entry-btn ${isKnowledgeBaseOpen ? 'active' : ''}`}
              onClick={() => setIsKnowledgeBaseOpen(true)}
            >
              <Database size={18} />
              <span>知识库</span>
            </button>
            <button
              className={`nav-item skills-entry-btn ${isSkillsPanelOpen ? 'active' : ''}`}
              onClick={() => setIsSkillsPanelOpen(true)}
            >
              <Sparkles size={18} />
              <span>Skills</span>
            </button>
          </nav>

          <div className="chats-section">
            <div className="chats-header">
              <span className="chats-title">Chats</span>
            </div>

            <div className="chats-list scrollbar-thin">
              {!currentUser ? (
                <div className="empty-chats">
                  <p>登录后查看历史会话</p>
                </div>
              ) : filteredSessions.length > 0 ? (
                filteredSessions.map((session) => (
                  <div
                    key={session.sessionId}
                    className={`chat-item ${session.sessionId === sessionId ? 'active' : ''} ${session.pinned ? 'pinned' : ''} ${openSessionMenu?.sessionId === session.sessionId ? 'menu-open' : ''}`}
                    onClick={() => handleSelectSession(session.sessionId)}
                  >
                    <div className="chat-item-content">
                      {session.pinned ? <Pin size={15} /> : <MessageSquare size={15} />}
                      <span className="chat-item-title" title={getSessionTitle(session)}>
                        {getSessionTitle(session)}
                      </span>
                    </div>
                    <div className="chat-item-meta">
                      <Clock size={12} />
                      <span className="chat-item-time">{formatTimeAgo(session.lastActive)}</span>
                      <div className="chat-menu-wrap">
                        <button
                          className="chat-menu-trigger"
                          ref={(node) => {
                            sessionMenuTriggerRefs.current[session.sessionId] = node;
                          }}
                          onClick={(event) => handleOpenSessionMenu(session.sessionId, event)}
                          title="更多操作"
                          aria-label={`${getSessionTitle(session)} 更多操作`}
                          aria-expanded={openSessionMenu?.sessionId === session.sessionId}
                        >
                          <Ellipsis size={15} />
                        </button>
                      </div>
                    </div>
                  </div>
                ))
              ) : (
                <div className="empty-chats">
                  <p>暂无历史会话</p>
                </div>
              )}
            </div>
          </div>

          <div className="sidebar-footer">
            <button className="settings-btn" onClick={() => setIsSettingsOpen(true)}>
              <Settings size={18} />
              <span>Settings</span>
            </button>
          </div>
        </div>
      </aside>

      <main className={`main-content ${isSidebarOpen ? 'with-sidebar' : ''}`}>
        <input
          type="file"
          ref={fileInputRef}
          onChange={handleFileInputChange}
          accept=".pdf,.doc,.docx,.txt,.html,.htm"
          hidden
        />
        <input
          type="file"
          ref={skillFileInputRef}
          onChange={handleSkillFileInputChange}
          accept=".skill,.zip"
          hidden
        />
        <nav className="top-nav">
          <div className="nav-left">
            {!isSidebarOpen && (
              <button className="sidebar-toggle" onClick={() => setIsSidebarOpen(true)}>
                <Menu size={20} />
              </button>
            )}
            <div className="nav-logo">JARVIS</div>
          </div>
          <div className="nav-actions">
            <button
              className={`workbench-toggle-btn ${isWorkbenchVisible ? 'active' : ''}`}
              onClick={() => setIsWorkbenchOpen((open) => !open)}
              title={isWorkbenchVisible ? '隐藏工作台' : hasWorkbenchTabs ? '展开工作台' : '打开工作台'}
              aria-label={isWorkbenchVisible ? '隐藏工作台' : '展开工作台'}
              aria-expanded={isWorkbenchVisible}
            >
              {isWorkbenchVisible ? <PanelRightClose size={17} /> : <PanelRightOpen size={17} />}
            </button>
            <button className="account-chip" onClick={() => setIsSettingsOpen(true)}>
              {currentUser ? <UserCircle size={17} /> : <LogIn size={17} />}
              <span>{currentUser?.username || 'Sign in'}</span>
            </button>
          </div>
        </nav>

        {!isChatMode && (
          <section className="hero-section">
            <h1 className="hero-title">
              Start everything<br />with JARVIS
            </h1>
            <p className="hero-subtitle">
              {currentUser ? 'What would you like to do?' : 'Sign in to start a protected JARVIS session.'}
            </p>

            <div className="quick-actions">
              {quickSuggestions.map((item) => (
                <button key={item.text} className="pill-btn" onClick={() => setInput(item.text)}>
                  {item.icon}
                  {item.text.length > 14 ? `${item.text.slice(0, 14)}...` : item.text}
                </button>
              ))}
            </div>

            <div className="glass-input-container hero-input">
              <div
                className={`glass-input-wrapper ${isDraggingFile ? 'is-dragging' : ''}`}
                onDragOver={handleDragOver}
                onDragLeave={handleDragLeave}
                onDrop={handleDrop}
              >
                {isDraggingFile && (
                  <div className="drag-overlay">
                    <Paperclip size={24} />
                    <span>释放文件以上传</span>
                  </div>
                )}
                {attachedFile && (
                  <div className="file-indicator">
                    <FileText size={14} />
                    <span className="file-name">{attachedFile.fileName}</span>
                    <button
                      type="button"
                      className="file-remove-btn"
                      onClick={handleRemoveFile}
                      aria-label="移除附件"
                    >
                      <X size={14} />
                    </button>
                  </div>
                )}
                {quotedResourceUris.length > 0 && (
                  <div className="quoted-resource-list">
                    {quotedResourceUris.map((uri) => (
                      <div key={uri} className="quoted-resource-chip" title={uri}>
                        <span className="quoted-resource-label">{uri}</span>
                        <button
                          type="button"
                          className="quoted-resource-remove"
                          onClick={() => handleRemoveQuotedResource(uri)}
                          aria-label={`移除引用 ${uri}`}
                        >
                          <X size={13} />
                        </button>
                      </div>
                    ))}
                  </div>
                )}
                  <textarea
                    ref={heroTextareaRef}
                    value={input}
                    onChange={handleInputChange}
                    onKeyDown={handleKeyDown}
                    placeholder={currentUser ? 'Type a prompt or press / for commands' : '请先登录账号'}
                    className="glass-textarea"
                    rows={1}
                    disabled={isComposerInputDisabled}
                  />
                <div className="input-toolbar">
                  <div className="toolbar-left">
                    <button
                      className="toolbar-icon-btn"
                      title="附件"
                      onClick={() => fileInputRef.current?.click()}
                      disabled={isUploadingFile}
                    >
                      {isUploadingFile ? <LoaderCircle size={17} className="spin" /> : <Paperclip size={17} />}
                    </button>
                    <button className="toolbar-icon-btn" title="联网搜索">
                      <Globe size={17} />
                    </button>
                  </div>
                  <div className="toolbar-right">
                    <span className="model-badge">
                      <Sparkles size={12} /> JARVIS
                    </span>
                    <button
                      className={`send-btn ${input.trim() ? 'active' : ''}`}
                      onClick={handleSend}
                      disabled={isSendDisabled}
                      title="发送"
                    >
                      {isLoading ? <Spinner /> : <ArrowUp size={16} strokeWidth={2.5} />}
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </section>
        )}

        <div
          className={`chat-area chat-scrollbar ${isChatMode ? 'visible' : ''} ${isChatScrolling ? 'is-scrolling' : ''}`}
          onScroll={handleChatScroll}
        >
          <div className="chat-messages">
            {messages.map((message) => {
              const hasPlainConversationCopy = Boolean(
                message.content?.trim() && !message.questionTrace && !message.answerTrace,
              );
              const bubbleClassName = [
                message.role === 'user' ? 'message-bubble-user' : 'message-bubble-ai',
                hasPlainConversationCopy ? 'message-bubble-readable' : '',
              ]
                .filter(Boolean)
                .join(' ');

              return (
                <div key={message.id} className={`message-row ${message.role === 'user' ? 'user' : 'ai'}`}>
                  <div className={bubbleClassName}>
                    {renderMessageContent(message)}
                    {getMessageMindmap(message) && (
                      <button
                        className="mindmap-tag"
                        onClick={() => {
                          const mindmap = getMessageMindmap(message);
                          if (mindmap) openMindmapInWorkbench(mindmap, message.id);
                        }}
                      >
                        <GitBranch size={14} />
                        查看思维导图
                      </button>
                    )}
                    {renderMessageArtifacts(message)}
                  </div>
                </div>
              );
            })}
            {isLoading && !streamingMessageId && (
              <div className="message-row ai">
                <div className="typing-indicator">
                  <div className="typing-dot" />
                  <div className="typing-dot" />
                  <div className="typing-dot" />
                </div>
              </div>
            )}
            <div ref={messagesEndRef} />
          </div>
        </div>

        <div className={`chat-input-bar ${isChatMode ? 'visible' : ''}`}>
          <div className="chat-input-bar-inner">
            {isStatusPanelOpen && (
              <div className="status-panel" aria-live="polite">
                <div className="status-panel-body">
                  {activeStatusTab === 'tasks' ? (
                    hasTasks ? (
                      <div className="task-list">
                        {taskPlan.map((task) => (
                          <div key={task.taskId} className={`task-row status-${task.status}`}>
                            <span className="task-status-icon" title={getTaskStatusLabel(task.status)}>
                              {task.status === 'completed' ? (
                                <Check size={18} />
                              ) : task.status === 'in_progress' ? (
                                <LoaderCircle size={18} />
                              ) : (
                                <Circle size={14} />
                              )}
                            </span>
                            <div className="task-copy">
                              <span className="task-title">{task.description}</span>
                              {task.detail && <span className="task-detail">{task.detail}</span>}
                            </div>
                            <span className="task-status-label">{getTaskStatusLabel(task.status)}</span>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <div className="status-empty">暂无任务运行</div>
                    )
                  ) : activeStatusTab === 'main' ? (
                    mainAgentState.visible ? (
                      <div className="run-step-list main-agent-panel">
                        <div className={`run-step-row status-${mainAgentState.phase === 'failed' ? 'failed' : mainAgentState.phase === 'success' ? 'success' : mainAgentState.phase === 'pending' ? 'pending' : 'running'} main-agent-row`}>
                          <span className="run-step-kind-icon kind-llm" title="Main Agent">
                            <Sparkles size={14} />
                          </span>
                          <div className="run-step-copy">
                            <div className="run-step-copy-main">
                              <strong>Main Agent</strong>
                              <span>{mainAgentState.detail}</span>
                            </div>
                            <div className="run-step-copy-meta">
                              <small>{mainAgentStatusLabel}</small>
                              <small>{`已运行 ${mainAgentElapsed}`}</small>
                            </div>
                          </div>
                          <span className={`run-step-status-pill status-${mainAgentState.phase === 'failed' ? 'failed' : mainAgentState.phase === 'success' ? 'success' : mainAgentState.phase === 'pending' ? 'pending' : 'running'}`}>
                            {mainAgentState.phase === 'success' ? (
                              <Check size={15} />
                            ) : mainAgentState.phase === 'failed' ? (
                              <X size={15} />
                            ) : mainAgentState.phase === 'pending' ? (
                              <Circle size={11} fill="currentColor" />
                            ) : (
                              <LoaderCircle size={15} className="spin" />
                            )}
                            <span>{mainAgentStatusLabel}</span>
                          </span>
                        </div>
                      </div>
                    ) : (
                      <div className="status-empty">暂无主 Agent 运行状态</div>
                    )
                  ) : (
                    subAgentSummaries.length > 0 ? (
                      <div className="sub-agent-list">
                        {subAgentSummaries.map((agent) => (
                          <div key={agent.id} className={`sub-agent-row status-${agent.status}`}>
                            <span className="sub-agent-empty-icon">
                              <Bot size={16} />
                            </span>
                            <div>
                              <strong>{agent.label}</strong>
                              <span>{getRunStepStatusLabel(agent.status)}</span>
                            </div>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <div className="sub-agent-shell">
                        <div className="sub-agent-empty-icon">
                          <Bot size={18} />
                        </div>
                        <div>
                          <strong>暂无子代理运行</strong>
                          <span>后续子代理会显示在这里</span>
                        </div>
                      </div>
                    )
                  )}
                </div>

                <div className="status-panel-tabs" role="tablist" aria-label="运行状态">
                  <button
                    type="button"
                    className={`status-tab ${activeStatusTab === 'tasks' ? 'active' : ''}`}
                    onClick={() => setActiveStatusTab('tasks')}
                    role="tab"
                    aria-selected={activeStatusTab === 'tasks'}
                  >
                    <ListChecks size={18} />
                    <span>任务</span>
                    {hasTasks && <b>{completedTasks}/{currentTaskProgress.total}</b>}
                  </button>
                  <button
                    type="button"
                    className={`status-tab ${activeStatusTab === 'main' ? 'active' : ''}`}
                    onClick={() => setActiveStatusTab('main')}
                    role="tab"
                    aria-selected={activeStatusTab === 'main'}
                  >
                    <Sparkles size={18} />
                    <span>主 Agent</span>
                    {mainAgentState.visible && <b>{mainAgentElapsed}</b>}
                  </button>
                  <button
                    type="button"
                    className={`status-tab ${activeStatusTab === 'agents' ? 'active' : ''}`}
                    onClick={() => setActiveStatusTab('agents')}
                    role="tab"
                    aria-selected={activeStatusTab === 'agents'}
                  >
                    <Bot size={18} />
                    <span>子代理</span>
                    {subAgentSummaries.length > 0 && <b>{subAgentSummaries.length}</b>}
                  </button>
                </div>
              </div>
            )}
            <div
              className={`glass-input-wrapper ${isDraggingFile ? 'is-dragging' : ''}`}
              onDragOver={handleDragOver}
              onDragLeave={handleDragLeave}
              onDrop={handleDrop}
            >
              {isDraggingFile && (
                <div className="drag-overlay">
                  <Paperclip size={24} />
                  <span>释放文件以上传</span>
                </div>
              )}
              {attachedFile && (
                <div className="file-indicator">
                  <FileText size={14} />
                  <span className="file-name">{attachedFile.fileName}</span>
                  <button
                    type="button"
                    className="file-remove-btn"
                    onClick={handleRemoveFile}
                    aria-label="移除附件"
                  >
                    <X size={14} />
                  </button>
                </div>
              )}
              {quotedResourceUris.length > 0 && (
                <div className="quoted-resource-list">
                  {quotedResourceUris.map((uri) => (
                    <div key={uri} className="quoted-resource-chip" title={uri}>
                      <span className="quoted-resource-label">{uri}</span>
                      <button
                        type="button"
                        className="quoted-resource-remove"
                        onClick={() => handleRemoveQuotedResource(uri)}
                        aria-label={`移除引用 ${uri}`}
                      >
                        <X size={13} />
                      </button>
                    </div>
                  ))}
                </div>
              )}
                <textarea
                  ref={chatTextareaRef}
                  value={input}
                  onChange={handleInputChange}
                  onKeyDown={handleKeyDown}
                  placeholder={currentUser ? '输入你的问题...' : '请先登录账号'}
                  className="glass-textarea"
                  rows={1}
                  disabled={isComposerInputDisabled}
                />
              <div className="input-toolbar">
                <div className="toolbar-left">
                  <button
                    className={`toolbar-icon-btn status-toggle-btn ${isStatusPanelOpen ? 'active' : ''}`}
                    title={isStatusPanelOpen ? '收起状态面板' : '展开状态面板'}
                    aria-label={isStatusPanelOpen ? '收起状态面板' : '展开状态面板'}
                    aria-expanded={isStatusPanelOpen}
                    onClick={() => setIsStatusPanelOpen((open) => !open)}
                  >
                    {isStatusPanelOpen ? <ChevronDown size={17} /> : <ChevronUp size={17} />}
                  </button>
                  <button
                    className="toolbar-icon-btn"
                    title="附件"
                    onClick={() => fileInputRef.current?.click()}
                    disabled={isUploadingFile}
                  >
                    {isUploadingFile ? <LoaderCircle size={17} className="spin" /> : <Paperclip size={17} />}
                  </button>
                  <button className="toolbar-icon-btn" title="联网搜索">
                    <Globe size={17} />
                  </button>
                </div>
                <div className="toolbar-right">
                  <span className="model-badge">
                    <Sparkles size={12} /> JARVIS
                  </span>
                  <button
                    className={`send-btn ${input.trim() ? 'active' : ''}`}
                    onClick={handleSend}
                    disabled={isSendDisabled}
                    title="发送"
                  >
                    {isLoading ? <Spinner /> : <ArrowUp size={16} strokeWidth={2.5} />}
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </main>

      {isWorkbenchVisible && (
        <aside
          className={`workbench-panel ${isResizingWorkbench ? 'is-resizing' : ''}`}
          aria-label="工作台"
        >
          <button
            type="button"
            className="workbench-resize-handle"
            onPointerDown={handleWorkbenchResizeStart}
            aria-label="拖动调整工作台宽度"
            title="拖动调整宽度"
          />
          <div className="workbench-panel-header">
            <div className="workbench-panel-title-group">
              <span className="workbench-panel-title">
                {activeWorkbenchMode === 'workspace' ? <Database size={18} /> : <GitBranch size={18} />}
                {activeWorkbenchMode === 'workspace' ? '工作空间' : '工作台'}
              </span>
              <div className="workbench-mode-switch" role="tablist" aria-label="右侧面板模式">
                <button
                  type="button"
                  className={`workbench-mode-option ${activeWorkbenchMode === 'workbench' ? 'active' : ''}`}
                  onClick={() => setActiveWorkbenchMode('workbench')}
                  role="tab"
                  aria-selected={activeWorkbenchMode === 'workbench'}
                >
                  <GitBranch size={14} />
                  <span>工作台</span>
                </button>
                <button
                  type="button"
                  className={`workbench-mode-option ${activeWorkbenchMode === 'workspace' ? 'active' : ''}`}
                  onClick={() => {
                    setActiveWorkbenchMode('workspace');
                    if (currentUser) {
                      void loadResourceDirectory(ROOT_WORKSPACE_PATH, 'workspace');
                    }
                  }}
                  role="tab"
                  aria-selected={activeWorkbenchMode === 'workspace'}
                >
                  <Database size={14} />
                  <span>工作空间</span>
                </button>
              </div>
            </div>
            <div className="workbench-panel-actions">
              <button
                className="workbench-panel-action"
                onClick={() => setIsWorkbenchOpen(false)}
                title="隐藏工作台"
                aria-label="隐藏工作台"
              >
                <PanelRightClose size={16} />
              </button>
            </div>
          </div>
          {activeWorkbenchMode === 'workbench' && (
            <div className="workbench-tabs" role="tablist" aria-label="工作台标签页">
              {workbenchTabs.length > 0 ? (
                workbenchTabs.map((tab) => (
                  <div
                    key={tab.id}
                    className={`workbench-tab ${activeWorkbenchTab && tab.id === activeWorkbenchTab.id ? 'active' : ''}`}
                    title={tab.title}
                  >
                    <button
                      type="button"
                      className="workbench-tab-main"
                      onClick={() => setActiveWorkbenchTabId(tab.id)}
                      role="tab"
                      aria-selected={activeWorkbenchTab ? tab.id === activeWorkbenchTab.id : false}
                    >
                      {renderWorkbenchTabIcon(tab)}
                      <span>{tab.title}</span>
                    </button>
                    <button
                      type="button"
                      className="workbench-tab-close"
                      onClick={(event) => handleCloseWorkbenchTab(tab.id, event)}
                      aria-label={`关闭 ${tab.title}`}
                      title="关闭标签"
                    >
                      <X size={13} />
                    </button>
                  </div>
                ))
              ) : (
                <div className="workbench-tabs-empty">暂无打开内容</div>
              )}
            </div>
          )}
          <div className="workbench-panel-body">
            {activeWorkbenchMode === 'workspace' ? (
              <div className="workbench-content-card workspace-content-card">
                {renderResourceBrowser('workspace')}
              </div>
            ) : activeWorkbenchTab ? (
              renderWorkbenchContent(activeWorkbenchTab)
            ) : (
              <div className="workbench-empty-state">
                <PanelRightOpen size={34} />
                <span>工作台已就绪</span>
                <p>思维导图、Markdown、简历预览等内容会在这里打开。</p>
              </div>
            )}
          </div>
        </aside>
      )}

      {(pendingQuestion || pendingQuestions.length > 0) && (
        <Suspense fallback={null}>
          <QuestionDialog
            question={pendingQuestion}
            questions={pendingQuestions}
            isOpen={isQuestionDialogOpen}
            onSubmit={handleQuestionAnswer}
            onClose={() => setIsQuestionDialogOpen(false)}
          />
        </Suspense>
      )}

      {isKnowledgeBaseOpen && createPortal(
        <div
          className="skills-panel-overlay"
          onClick={() => setIsKnowledgeBaseOpen(false)}
        >
          <section
            className="skills-panel knowledge-base-panel"
            role="dialog"
            aria-modal="true"
            aria-labelledby="knowledge-base-panel-title"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="skills-panel-header">
              <div className="skills-panel-heading">
                <span className="skills-panel-eyebrow">Knowledge Base</span>
                <h2 id="knowledge-base-panel-title">知识库</h2>
              </div>
              <button
                type="button"
                className="skills-panel-close"
                onClick={() => setIsKnowledgeBaseOpen(false)}
                aria-label="关闭知识库面板"
              >
                <X size={16} />
              </button>
            </div>

            <div className="skills-panel-body">
              <div className="skills-panel-section knowledge-base-section">
                <input
                  type="file"
                  ref={knowledgeBaseFileInputRef}
                  onChange={handleKnowledgeBaseFileInputChange}
                  accept={KNOWLEDGE_BASE_ACCEPT}
                  multiple
                  hidden
                />

                <div className="knowledge-base-card knowledge-base-resource-browser">
                  <div className="knowledge-base-card-head">
                    <span className="skills-existing-panel-eyebrow">Filesystem</span>
                    <h3>资源目录</h3>
                  </div>

                  <div className="knowledge-base-resource-toolbar">
                    <div className="knowledge-base-resource-toolbar-main">
                      <input
                        type="text"
                        className="knowledge-base-resource-uri"
                        value={resourceDirectoryInput}
                        onChange={(event) => setResourceDirectoryInput(event.target.value)}
                        onBlur={() => {
                          if (!resourceDirectoryInput.trim()) {
                            setResourceDirectoryInput(resourceDirectoryPath);
                          }
                        }}
                        onKeyDown={(event) => handleResourceDirectoryInputKeyDown(event, 'knowledge-base')}
                        aria-label="资源目录路径"
                        title="按 Enter 加载路径"
                        spellCheck={false}
                      />
                    </div>
                    <div className="knowledge-base-resource-toolbar-actions">
                      <div className="knowledge-base-resource-controls">
                        <button
                          type="button"
                          className="knowledge-base-resource-control"
                          onClick={() => handleGoBackResourceDirectory('knowledge-base')}
                          aria-label="返回上级目录"
                          title="返回上级目录"
                        >
                          <ArrowLeft size={14} />
                        </button>
                        <button
                          type="button"
                          className="knowledge-base-resource-control"
                          onClick={() => handleRefreshResourceDirectory('knowledge-base')}
                          aria-label="刷新目录"
                          title="刷新目录"
                        >
                          <RefreshCw size={14} />
                        </button>
                        <button
                          type="button"
                          className="knowledge-base-resource-control danger"
                          onClick={handleDeleteSelectedResource}
                          aria-label="删除选中资源"
                          title="删除选中资源"
                          disabled={!selectedResource || isDeletingResource}
                        >
                          {isDeletingResource ? <LoaderCircle size={14} className="spin" /> : <Trash2 size={14} />}
                        </button>
                        <button
                          type="button"
                          className="knowledge-base-resource-control primary"
                          onClick={() => handleEnterResourceDirectory('knowledge-base')}
                          aria-label="进入路径或选中目录"
                          title="进入路径或选中目录"
                        >
                          <CornerDownRight size={14} />
                        </button>
                      </div>
                      <span className="knowledge-base-resource-count">{resourceItems.length} 个条目</span>
                    </div>
                  </div>

                  <div className="knowledge-base-resource-grid">
                    <div className="knowledge-base-resource-table">
                      <div className="knowledge-base-resource-table-head">
                        <span>URI</span>
                        <span>大小</span>
                        <span>类型</span>
                        <span>更新时间</span>
                        <span>引用</span>
                      </div>
                      <div className="knowledge-base-resource-table-body">
                        {isResourceLoading ? (
                          <div className="skills-existing-empty">
                            <LoaderCircle size={18} className="animate-spin" />
                            <p>加载资源目录...</p>
                          </div>
                        ) : resourceError ? (
                          <div className="skills-existing-empty knowledge-base-resource-empty">
                            <X size={18} />
                            <p>{resourceError}</p>
                          </div>
                        ) : resourceItems.length === 0 ? (
                          <div className="skills-existing-empty">
                            <Database size={18} />
                            <p>当前目录为空</p>
                          </div>
                        ) : (
                          resourceItems.map((item) => (
                            <button
                              type="button"
                              key={item.uri}
                              className={`knowledge-base-resource-row ${selectedResource?.uri === item.uri ? 'active' : ''}`}
                              onClick={() => setSelectedResourceUri(item.uri)}
                              onDoubleClick={() => {
                                setSelectedResourceUri(item.uri);
                                if (isResourceDirectoryLike(item)) {
                                  openResourceDirectory(item.uri, 'knowledge-base');
                                }
                              }}
                            >
                              <span className="resource-uri" title={item.uri}>{item.uri}</span>
                              <span>{item.size ? formatResourceSize(item.size) : '-'}</span>
                              <span>{isResourceDirectoryLike(item) ? '目录' : item.type || '文件'}</span>
                              <span>{item.updatedAt || '-'}</span>
                              <span className="knowledge-base-resource-row-action">
                                <button
                                  type="button"
                                  className="knowledge-base-resource-quote"
                                  onClick={(event) => {
                                    event.stopPropagation();
                                    handleQuoteResource(item.uri);
                                  }}
                                  aria-label={`引用资源 ${item.uri}`}
                                  title="引用到聊天输入框"
                                >
                                  <span>引用</span>
                                </button>
                              </span>
                            </button>
                          ))
                        )}
                      </div>
                    </div>

                    <div className="knowledge-base-resource-detail">
                      {isResourceDetailLoading ? (
                        <div className="skills-existing-empty detail">
                          <LoaderCircle size={18} className="animate-spin" />
                          <p>加载资源详情...</p>
                        </div>
                      ) : selectedResourceDetail ? (
                        <>
                          <div className="knowledge-base-resource-detail-head">
                            <strong>{selectedResourceDetail.name || selectedResourceDetail.uri}</strong>
                            <div className="knowledge-base-resource-detail-topline">
                              <span>{selectedResourceDetail.directory ? '目录概览' : '文件概览'}</span>
                              <div className="knowledge-base-resource-mini-meta">
                                <label>大小</label>
                                <strong>{selectedResourceDetail.size ? formatResourceSize(selectedResourceDetail.size) : '-'}</strong>
                              </div>
                              <div className="knowledge-base-resource-mini-meta">
                                <label>类型</label>
                                <strong>{selectedResourceDetail.directory ? 'Directory' : selectedResourceDetail.type || 'File'}</strong>
                              </div>
                              <div className="knowledge-base-resource-mini-meta">
                                <label>修改时间</label>
                                <strong>{selectedResourceDetail.updatedAt || '-'}</strong>
                              </div>
                            </div>
                          </div>
                          <div className="knowledge-base-resource-preview-panel">
                            <div className="knowledge-base-resource-preview-head">
                              <span>Preview</span>
                            </div>
                            {selectedResourceDetail.preview ? (
                              <pre className="knowledge-base-resource-preview-content">
                                {selectedResourceDetail.preview}
                              </pre>
                            ) : selectedResourceDetail.abstractText || selectedResourceDetail.overviewText ? (
                              <div className="knowledge-base-resource-preview-empty">
                                <p>{selectedResourceDetail.overviewText || selectedResourceDetail.abstractText}</p>
                              </div>
                            ) : (
                              <div className="knowledge-base-resource-preview-empty">
                                <span>{selectedResourceDetail.directory ? '目录暂无概览内容。' : '当前文件暂无预览。'}</span>
                              </div>
                            )}
                          </div>
                        </>
                      ) : (
                        <div className="skills-existing-empty detail knowledge-base-resource-empty">
                          <Database size={18} />
                          <p>选中一个资源条目后，这里展示目录概览或文件摘要。</p>
                        </div>
                      )}
                    </div>
                  </div>
                </div>

                <div className="knowledge-base-card">
                  <div className="knowledge-base-card-head">
                    <span className="skills-existing-panel-eyebrow">文件</span>
                    <h3>添加资源来源</h3>
                  </div>

                  <button
                    type="button"
                    className={`skills-upload-placeholder knowledge-base-upload ${knowledgeBaseUploadSummary ? 'uploaded' : ''} ${isDraggingKnowledgeBaseFiles ? 'dragging' : ''}`}
                    onClick={() => knowledgeBaseFileInputRef.current?.click()}
                    onDragOver={handleKnowledgeBaseDragOver}
                    onDragLeave={handleKnowledgeBaseDragLeave}
                    onDrop={handleKnowledgeBaseDrop}
                  >
                    {knowledgeBaseUploadSummary ? (
                      <div className="skills-upload-success-inline">
                        <span className="skills-upload-success-status">
                          <Check size={16} />
                          <span>资源添加成功</span>
                        </span>
                        <strong>{knowledgeBaseUploadSummary}</strong>
                        <p>目录已刷新，可以继续添加新的文件来源。</p>
                      </div>
                    ) : (
                      <>
                        <div className="skills-upload-icon">
                          <Upload size={20} />
                        </div>
                        <strong>添加资源文件</strong>
                        <p>支持多文件上传，也支持仓库 URL、raw 文件 URL 作为来源。</p>
                      </>
                    )}
                  </button>

                  <label className="knowledge-base-url-field">
                    <span>仓库 / Raw 文件 URL</span>
                    <input
                      type="text"
                      value={knowledgeBaseSourceUrl}
                      onChange={(event) => setKnowledgeBaseSourceUrl(event.target.value)}
                      placeholder="git://... / GitHub / GitLab / Bitbucket 仓库地址或 raw 文件 URL"
                    />
                  </label>

                  {knowledgeBaseFiles.length > 0 && !knowledgeBaseUploadSummary && (
                    <div className="knowledge-base-file-list">
                      {knowledgeBaseFiles.map((file) => (
                        <div
                          key={`${file.name}-${file.size}-${file.lastModified}`}
                          className="knowledge-base-file-item"
                        >
                          <div className="knowledge-base-file-copy">
                            <strong>{file.name}</strong>
                            <span>{`${(file.size / 1024).toFixed(1)} KB`}</span>
                          </div>
                          <button
                            type="button"
                            className="knowledge-base-file-remove"
                            aria-label={`移除 ${file.name}`}
                            onClick={() => handleRemoveKnowledgeBaseFile(file)}
                          >
                            <X size={14} />
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                </div>

                <div className="knowledge-base-card">
                  <div className="knowledge-base-card-head">
                    <span className="skills-existing-panel-eyebrow">文本</span>
                    <h3>纯文本资源</h3>
                  </div>

                  <label className="knowledge-base-url-field knowledge-base-inline-field">
                    <span>资源文件名</span>
                    <input
                      type="text"
                      value={knowledgeBaseTextFileName}
                      onChange={(event) => setKnowledgeBaseTextFileName(event.target.value)}
                      placeholder="例如：我的长期笔记（保存时会写成 我的长期笔记.md）"
                    />
                  </label>

                  <label className="knowledge-base-text-field">
                    <span>资源正文</span>
                    <textarea
                      value={knowledgeBaseText}
                      onChange={(event) => setKnowledgeBaseText(event.target.value)}
                      placeholder="在这里粘贴非常长的纯文本内容。添加资源时会按上面的文件名自动保存为 .md 文档。"
                      rows={10}
                    />
                  </label>
                </div>

                <div className="knowledge-base-footer">
                  {knowledgeBaseFeedback ? (
                    <p className="knowledge-base-feedback">{knowledgeBaseFeedback}</p>
                  ) : (
                    <p className="knowledge-base-feedback muted">支持文档、表格/演示、代码文件，以及仓库链接来源。</p>
                  )}
                  <div className="knowledge-base-actions">
                    <button
                      type="button"
                      className="knowledge-base-action secondary"
                      onClick={handleClearKnowledgeBase}
                    >
                      清除
                    </button>
                    <button
                      type="button"
                      className="knowledge-base-action primary"
                      onClick={handleConfirmKnowledgeBase}
                    >
                      添加资源
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </section>
        </div>,
        document.body,
      )}

      {isSkillsPanelOpen && createPortal(
        <div
          className="skills-panel-overlay"
          onClick={() => setIsSkillsPanelOpen(false)}
        >
          <section
            className="skills-panel"
            role="dialog"
            aria-modal="true"
            aria-labelledby="skills-panel-title"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="skills-panel-header">
              <div className="skills-panel-heading">
                <span className="skills-panel-eyebrow">Future Skills</span>
                <h2 id="skills-panel-title">Skills</h2>
              </div>
              <button
                type="button"
                className="skills-panel-close"
                onClick={() => setIsSkillsPanelOpen(false)}
                aria-label="关闭 Skills 面板"
              >
                <X size={16} />
              </button>
            </div>

            <div className="skills-panel-tabs" role="tablist" aria-label="Skills 模式切换">
              <button
                type="button"
                role="tab"
                aria-selected={activeSkillsTab === 'existing'}
                className={`skills-panel-tab ${activeSkillsTab === 'existing' ? 'active' : ''}`}
                onClick={() => setActiveSkillsTab('existing')}
              >
                <Sparkles size={15} />
                <span>已有 skill</span>
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={activeSkillsTab === 'add'}
                className={`skills-panel-tab ${activeSkillsTab === 'add' ? 'active' : ''}`}
                onClick={() => setActiveSkillsTab('add')}
              >
                <Plus size={15} />
                <span>添加 skill</span>
              </button>
            </div>

            <div className="skills-panel-body">
              {activeSkillsTab === 'existing' ? (
                <div className="skills-panel-section">
                  <div className="skills-existing-list-panel">
                    <div className="skills-existing-panel-head">
                      <div>
                        <span className="skills-existing-panel-eyebrow">OV Skills</span>
                        <h3>技能列表</h3>
                      </div>
                      <span className="skills-existing-count">{existingSkills.length} 个</span>
                    </div>

                    <div className="skills-existing-list">
                      {isExistingSkillsLoading ? (
                        <div className="skills-existing-empty">
                          <LoaderCircle size={18} className="spin-icon" />
                          <p>正在加载当前用户的 OV skills...</p>
                        </div>
                      ) : existingSkillsError ? (
                        <div className="skills-existing-empty">
                          <X size={18} />
                          <p>{existingSkillsError}</p>
                        </div>
                      ) : existingSkills.length > 0 ? existingSkills.map((skill) => (
                        <div
                          key={skill.id}
                          className={`skills-existing-item ${selectedExistingSkill?.id === skill.id ? 'active' : ''}`}
                        >
                          <button
                            type="button"
                            className="skills-existing-item-main"
                            onClick={() => setSelectedExistingSkillId(skill.id)}
                          >
                            <div className="skills-existing-item-copy">
                              <strong>{skill.name}</strong>
                              <span>{skill.path}</span>
                            </div>
                            <small>{formatSkillUpdatedAt(skill.updatedAt)}</small>
                          </button>
                          <button
                            type="button"
                            className="skills-existing-delete"
                            aria-label={`删除 ${skill.name}`}
                            onClick={() => handleDeleteExistingSkill(skill.id)}
                          >
                            <Trash2 size={15} />
                          </button>
                        </div>
                      )) : (
                        <div className="skills-existing-empty">
                          <Sparkles size={18} />
                          <p>当前用户在 OV skills 目录下还没有可展示的 skill。</p>
                        </div>
                      )}
                    </div>
                  </div>

                  <div className="skills-existing-detail-panel">
                    <div className="skills-existing-panel-head">
                      <div>
                        <span className="skills-existing-panel-eyebrow">Abstract</span>
                        <h3>技能描述</h3>
                      </div>
                    </div>

                    {selectedExistingSkill ? (
                      <div className="skills-existing-detail-body">
                        <div className="skills-existing-detail-title">
                          <strong>{selectedExistingSkill.name}</strong>
                          <span>{selectedExistingSkill.path}</span>
                        </div>
                        <p>{selectedExistingSkill.abstract}</p>
                      </div>
                    ) : (
                      <div className="skills-existing-empty detail">
                        <Sparkles size={18} />
                        <p>选中一个 skill 后，这里展示 OV 返回的 `abstract` 描述。</p>
                      </div>
                    )}
                  </div>
                </div>
              ) : (
                <div className="skills-panel-section">
                  <button
                    type="button"
                    className={`skills-upload-placeholder ${isDraggingSkillFile ? 'dragging' : ''} ${uploadedSkill ? 'uploaded' : ''} ${skillUploadError ? 'error' : ''}`}
                    onClick={() => skillFileInputRef.current?.click()}
                    onDragOver={handleSkillDragOver}
                    onDragLeave={handleSkillDragLeave}
                    onDrop={handleSkillDrop}
                    disabled={isUploadingSkill}
                  >
                    <div className="skills-upload-icon">
                      {isUploadingSkill ? (
                        <LoaderCircle size={20} className="spin-icon" />
                      ) : uploadedSkill ? (
                        <FileText size={20} />
                      ) : skillUploadError ? (
                        <FileText size={20} />
                      ) : (
                        <Upload size={20} />
                      )}
                    </div>
                    {uploadedSkill ? (
                      <div className="skills-upload-success-inline">
                        <strong>{uploadedSkill.fileName || '未命名文件'}</strong>
                        <p className="skills-upload-success-status">
                          <Check size={14} />
                          <span>{uploadedSkill.message || '文件上传并解析成功'}</span>
                        </p>
                      </div>
                    ) : skillUploadError ? (
                      <div className="skills-upload-success-inline">
                        <strong>{skillUploadFileName || '上传失败'}</strong>
                        <p className="skills-upload-success-status error">
                          <X size={14} />
                          <span>{skillUploadError}</span>
                        </p>
                      </div>
                    ) : (
                      <>
                        <strong>{isUploadingSkill ? '正在上传并解析 skill' : '上传 skill 智能解析'}</strong>
                        <p>支持 zip 或 .skill 文件。点击上传或直接拖拽到这里。</p>
                      </>
                    )}
                  </button>

                  <label className="skills-instruction-field">
                    <span>技能指令（暂未开放）</span>
                    <textarea
                      value={skillInstructionDraft}
                      onChange={(event) => setSkillInstructionDraft(event.target.value)}
                      placeholder="定义这个 skill 被激活时的指令、规则和使用场景。"
                      rows={8}
                    />
                  </label>
                </div>
              )}
            </div>
          </section>
        </div>,
        document.body,
      )}

      {openSessionMenu && createPortal(
        (() => {
          const menuSession = sessions.find((session) => session.sessionId === openSessionMenu.sessionId);
          if (!menuSession) return null;

          return (
            <div
              ref={sessionMenuRef}
              className="chat-action-menu chat-action-menu-floating"
              style={{
                top: `${openSessionMenu.top}px`,
                left: `${openSessionMenu.left}px`,
              }}
              onClick={(event) => event.stopPropagation()}
              onPointerDown={(event) => event.stopPropagation()}
            >
              <button onClick={(event) => handleTogglePinSession(menuSession, event)}>
                {menuSession.pinned ? <PinOff size={16} /> : <Pin size={16} />}
                <span>{menuSession.pinned ? '取消置顶' : '置顶'}</span>
              </button>
              <button onClick={(event) => handleStartRenameSession(menuSession, event)}>
                <Pencil size={16} />
                <span>重命名</span>
              </button>
              <button
                className="danger"
                onClick={(event) => handleDeleteSession(menuSession.sessionId, event)}
              >
                <Trash2 size={16} />
                <span>删除</span>
              </button>
            </div>
          );
        })(),
        document.body,
      )}

      {renameSessionTarget && (
        <div
          className="rename-dialog-overlay"
          onClick={() => setRenameSessionTarget(null)}
        >
          <form className="rename-dialog" onSubmit={handleRenameSubmit} onClick={(event) => event.stopPropagation()}>
            <div className="rename-dialog-header">
              <span>重命名对话</span>
              <button type="button" onClick={() => setRenameSessionTarget(null)} aria-label="关闭">
                <X size={16} />
              </button>
            </div>
            <label className="rename-field">
              <span>名称</span>
              <input
                ref={renameInputRef}
                value={renameTitle}
                onChange={(event) => setRenameTitle(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Escape') setRenameSessionTarget(null);
                }}
                maxLength={200}
                placeholder="新对话"
              />
            </label>
            <div className="rename-dialog-footer">
              <button type="button" className="rename-cancel-btn" onClick={() => setRenameSessionTarget(null)}>
                取消
              </button>
              <button type="submit" className="rename-save-btn">
                保存
              </button>
            </div>
          </form>
        </div>
      )}

      {isSettingsOpen && (
        <Suspense fallback={<LazyOverlayFallback label="正在加载设置..." />}>
          <SettingsPage
            onClose={() => setIsSettingsOpen(false)}
            user={currentUser}
            onAuthenticated={handleAuthenticated}
            onLogout={handleLogout}
            wallpaperUrl={wallpaperUrl}
            defaultWallpaperUrl={DEFAULT_WALLPAPER_URL}
            onWallpaperChange={handleWallpaperChange}
          />
        </Suspense>
      )}
    </div>
  );
}

function Spinner() {
  return <div className="spinner-dot" />;
}

function LazySectionFallback({ label }: { label: string }) {
  return (
    <div className="workbench-content-card workbench-loading-card">
      <Spinner />
      <span>{label}</span>
    </div>
  );
}

function LazyOverlayFallback({ label }: { label: string }) {
  return (
    <div className="settings-page settings-page-loading">
      <div className="settings-loading-card">
        <Spinner />
        <span>{label}</span>
      </div>
    </div>
  );
}
