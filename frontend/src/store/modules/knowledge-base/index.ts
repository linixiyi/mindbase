import { NButton } from 'naive-ui';
import { router } from '@/router';
import { REQUEST_ID_KEY } from '~/packages/axios/src';
import { nanoid } from '~/packages/utils/src';

const maxConcurrentChunksPerFile = 4;

export const useKnowledgeBaseStore = defineStore(SetupStoreId.KnowledgeBase, () => {
  const tasks = ref<Api.KnowledgeBase.UploadTask[]>([]);
  const activeUploads = ref<Set<string>>(new Set());

  function mergeUploadedChunks(currentUploadedChunks: number[], latestUploadedChunks: number[]) {
    return Array.from(new Set([...currentUploadedChunks, ...latestUploadedChunks])).sort((a, b) => a - b);
  }

  async function uploadChunk(task: Api.KnowledgeBase.UploadTask, chunkIndex: number): Promise<boolean> {
    const totalChunks = Math.ceil(task.totalSize / chunkSize);

    const chunkStart = chunkIndex * chunkSize;
    const chunkEnd = Math.min(chunkStart + chunkSize, task.totalSize);
    const chunk = task.file.slice(chunkStart, chunkEnd);

    const requestId = nanoid();
    task.requestIds ??= [];
    task.requestIds.push(requestId);
    const { error, data } = await request<Api.KnowledgeBase.Progress>({
      url: '/upload/chunk',
      method: 'POST',
      data: {
        file: chunk,
        fileMd5: task.fileMd5,
        chunkIndex,
        totalSize: task.totalSize,
        fileName: task.fileName,
        orgTag: task.orgTag,
        isPublic: task.isPublic ?? false
      },
      headers: {
        'Content-Type': 'multipart/form-data',
        [REQUEST_ID_KEY]: requestId
      },
      timeout: 10 * 60 * 1000
    });

    task.requestIds = task.requestIds.filter(id => id !== requestId);

    if (error) return false;

    // 更新任务状态
    const updatedTask = tasks.value.find(t => t.fileMd5 === task.fileMd5);
    if (!updatedTask) return true;

    updatedTask.chunkIndex = chunkIndex;
    updatedTask.uploadedChunks = mergeUploadedChunks(updatedTask.uploadedChunks, data.uploaded);
    updatedTask.progress = Number.parseFloat(((updatedTask.uploadedChunks.length / totalChunks) * 100).toFixed(2));

    return true;
  }

  async function uploadChunksInParallel(task: Api.KnowledgeBase.UploadTask, chunkIndexes: number[]) {
    if (chunkIndexes.length === 0) return;

    let uploadError: Error | null = null;
    const workerCount = Math.min(maxConcurrentChunksPerFile, chunkIndexes.length);
    const runWorker = async (): Promise<void> => {
      if (uploadError) return;

      const chunkIndex = chunkIndexes.shift();
      if (chunkIndex === undefined) return;

      const success = await uploadChunk(task, chunkIndex);
      if (!success) {
        uploadError = new Error(`分片 ${chunkIndex} 上传失败`);
        return;
      }

      await runWorker();
    };
    const workers = Array.from({ length: workerCount }, () => runWorker());

    await Promise.all(workers);

    if (uploadError) throw uploadError;
  }

  async function mergeFile(task: Api.KnowledgeBase.UploadTask) {
    try {
      const { error, data } = await request<Api.KnowledgeBase.MergeResult>({
        url: '/upload/merge',
        method: 'POST',
        data: { fileMd5: task.fileMd5, fileName: task.fileName }
      });
      if (error) return false;

      // 更新任务状态为已完成
      const index = tasks.value.findIndex(t => t.fileMd5 === task.fileMd5);
      tasks.value[index].status = UploadStatus.Completed;
      tasks.value[index].progress = 100;
      tasks.value[index].estimatedEmbeddingTokens = data?.estimatedEmbeddingTokens;
      tasks.value[index].estimatedChunkCount = data?.estimatedChunkCount;
      tasks.value[index].vectorizationStatus = 'PROCESSING';
      tasks.value[index].vectorizationErrorMessage = null;
      tasks.value[index].actualEmbeddingTokens = undefined;
      tasks.value[index].actualChunkCount = undefined;

      if (data?.estimatedEmbeddingTokens) {
        const tokenLabel = Number(data.estimatedEmbeddingTokens).toLocaleString();
        const chunkLabel = Number(data.estimatedChunkCount || 0).toLocaleString();
        window.$message?.success(`上传完成，预计向量化消耗 ${tokenLabel} Tokens（${chunkLabel} 个切片）`);
      }
      return true;
    } catch {
      return false;
    }
  }

  /**
   * 异步函数：将上传请求加入队列（pm-024c：多文件逐个入队，每文件独立任务/独立进度与成败态）
   *
   * 逐文件计算 MD5 并查重：已完成/在途的重复文件跳过并汇总提示；中断中的同名文件回填文件对象后重新入队续传。 分片队列内核不变：startUpload 按并发上限逐个消费待上传任务。
   *
   * @param form 包含上传信息的表单，包括文件列表和是否公开的标签
   * @returns 本次提交涉及（新建或恢复续传）的上传任务列表；全部被跳过时为空数组
   */
  async function enqueueUpload(form: Api.KnowledgeBase.Form): Promise<Api.KnowledgeBase.UploadTask[]> {
    const files = (form.fileList ?? []).map(item => item.file).filter((file): file is File => Boolean(file));
    const involvedTasks: Api.KnowledgeBase.UploadTask[] = [];
    const skippedNames: string[] = [];

    // 逐文件顺序处理（大文件 MD5 需数秒，避免并发读多个大文件），MD5 读取失败只跳过该文件
    const enqueueFile = async (index: number): Promise<void> => {
      const file = files[index];
      if (!file) return;

      let md5: string;
      try {
        md5 = await calculateMD5(file);
      } catch (error) {
        window.$message?.error(
          `「${file.name}」读取失败，已跳过：${error instanceof Error ? error.message : '未知错误'}`
        );
        await enqueueFile(index + 1);
        return;
      }

      // 检查是否已存在相同文件
      const existingTask = tasks.value.find(t => t.fileMd5 === md5);
      if (
        existingTask &&
        (existingTask.status === UploadStatus.Completed ||
          existingTask.status === UploadStatus.Pending ||
          existingTask.status === UploadStatus.Uploading)
      ) {
        skippedNames.push(file.name);
        await enqueueFile(index + 1);
        return;
      }

      if (existingTask) {
        // 断点任务可能来自服务端恢复（无本地 File 对象），回填本次选择的文件才能续传分片
        existingTask.file ??= file;
        existingTask.status = UploadStatus.Pending;
        involvedTasks.push(existingTask);
        await enqueueFile(index + 1);
        return;
      }

      // 创建新的上传任务对象（每文件独立：失败只中断自身，其余文件继续）
      const newTask: Api.KnowledgeBase.UploadTask = {
        file,
        chunk: null,
        chunkIndex: 0,
        fileMd5: md5,
        fileName: file.name,
        totalSize: file.size,
        public: form.isPublic,
        isPublic: form.isPublic,
        uploadedChunks: [],
        progress: 0,
        status: UploadStatus.Pending,
        orgTag: form.orgTag,
        vectorizationStatus: null,
        vectorizationErrorMessage: null
      };

      newTask.orgTagName = form.orgTagName ?? null;

      // 将新的上传任务添加到任务队列中
      tasks.value.push(newTask);
      involvedTasks.push(newTask);
      await enqueueFile(index + 1);
    };

    await enqueueFile(0);

    if (skippedNames.length) {
      window.$message?.warning(
        `${skippedNames.length} 个文件已在知识库中或正在上传，已跳过：${skippedNames.join('、')}`
      );
    }

    if (involvedTasks.length) {
      // 启动上传流程（store 按并发上限逐个消费队列）
      startUpload();
    }
    return involvedTasks;
  }

  /**
   * 分片/合并失败的全局兜底通知（pm-019）
   *
   * 上传由应用级 Pinia store 驱动，失败可能发生在用户已离开知识库页之后—— 仅把任务置为 Break（列表行内状态）是无声失败，必须给全局可见反馈与「去知识库」入口。
   */
  function notifyUploadFailure(task: Api.KnowledgeBase.UploadTask, phase: string) {
    const notification = window.$notification?.create({
      title: '文件上传失败',
      content: `「${task.fileName}」${phase}，已保留已上传分片，可在知识库文件列表续传。`,
      action: () =>
        h(
          'div',
          { style: { display: 'flex', justifyContent: 'flex-end', width: '100%' } },
          h(
            NButton,
            {
              size: 'small',
              type: 'primary',
              onClick() {
                notification?.destroy();
                router.push({ path: '/knowledge-base' });
              }
            },
            () => '去知识库'
          )
        )
    });
  }

  /** 启动文件上传的异步函数 该函数负责从待上传队列中启动文件上传任务，并管理并发上传的数量 */
  async function startUpload() {
    // 限制可同时上传的文件个数
    if (activeUploads.value.size >= 3) return;
    // 获取待上传的文件
    const pendingTasks = tasks.value.filter(
      t => t.status === UploadStatus.Pending && !activeUploads.value.has(t.fileMd5)
    );

    // 如果没有待上传的文件，则直接返回
    if (pendingTasks.length === 0) return;

    // 获取第一个待上传的文件
    const task = pendingTasks[0];
    task.status = UploadStatus.Uploading;
    activeUploads.value.add(task.fileMd5);

    // 计算文件总片数
    const totalChunks = Math.ceil(task.totalSize / chunkSize);

    try {
      if (task.uploadedChunks.length === totalChunks) {
        const success = await mergeFile(task);
        if (!success) throw new Error('文件合并失败');
        return;
      }

      const pendingChunkIndexes: number[] = [];
      for (let i = 0; i < totalChunks; i += 1) {
        if (!task.uploadedChunks.includes(i)) {
          pendingChunkIndexes.push(i);
        }
      }

      await uploadChunksInParallel(task, pendingChunkIndexes);

      const updatedTask = tasks.value.find(t => t.fileMd5 === task.fileMd5);
      if (!updatedTask) return;

      if (updatedTask.uploadedChunks.length !== totalChunks) {
        throw new Error('分片上传未完成');
      }

      const success = await mergeFile(updatedTask);
      if (!success) throw new Error('文件合并失败');
    } catch (error) {
      // 如果上传失败，则将任务状态设置为中断，并给全局可见反馈（不再无声失败）
      const index = tasks.value.findIndex(t => t.fileMd5 === task.fileMd5);
      tasks.value[index].status = UploadStatus.Break;
      const phase = error instanceof Error && error.message.includes('合并') ? '分片合并失败' : '分片上传失败';
      notifyUploadFailure(tasks.value[index], phase);
    } finally {
      // 无论成功或失败，都从活跃队列中移除
      activeUploads.value.delete(task.fileMd5);
      // 继续下一个任务
      startUpload();
    }
  }

  return {
    tasks,
    activeUploads,
    enqueueUpload,
    startUpload
  };
});
