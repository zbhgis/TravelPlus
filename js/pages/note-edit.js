/**
 * 【游记发布页的实现】#/edit 页面渲染模块
 * 职责：标题/正文输入、图片多选上传（最多9张）、图片删除、游记发布
 * 依赖：api.js (post/get/del)、ui.js (imgUrl/imgError/toast)、store.js (isLoggedIn)
 * 核心链路：
 *   renderNoteEdit(container)
 *     → 未登录 → 重定向 #/login?redirect=edit
 *     → 已登录 → 渲染表单（标题 input + 正文 textarea + 图片上传区 + 发布按钮）
 *     → 图片上传：选文件 → FormData POST /upload/note → 返回相对路径名 → 加入 selectedFiles[] → 渲染预览
 *     → 图片删除：× 按钮 → GET /upload/note/delete?name= → 从 selectedFiles 移除 → 重渲染预览
 *     → 发布：校验标题/正文非空 → POST /note {title, content, images(逗号分隔)} → toast → 跳 #/note/{id}
 * 注意：图片上传使用 FormData（api.js 自动检测不设 Content-Type），selectedFiles 存服务端返回的文件名字符串
 */
import { post, get, del } from '../api.js';
import { imgUrl, imgError, toast } from '../ui.js';
import { isLoggedIn } from '../store.js';

let selectedFiles = [];

export async function renderNoteEdit(container) {
  if (!isLoggedIn()) {
    window.location.hash = '#/login?redirect=edit';
    return;
  }

  container.innerHTML = `
    <div class="page-header">
      <button class="header-back" id="edit-back">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
      </button>
      <h2>发布游记</h2>
    </div>
    <div class="edit-form">
      <div class="form-group">
        <label>标题</label>
        <input type="text" id="edit-title" placeholder="给游记起个标题"/>
      </div>
      <div class="form-group">
        <label>正文</label>
        <textarea id="edit-content" placeholder="分享你的旅行故事..." rows="6"></textarea>
      </div>
      <div class="form-group">
        <label>图片（最多9张）</label>
        <div class="image-upload-list" id="image-list">
          <label class="image-upload-add" id="image-add-btn">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="M12 8v8"/><path d="M8 12h8"/></svg>
          </label>
        </div>
        <input type="file" id="image-input" accept="image/*" multiple hidden/>
      </div>
      <button class="btn btn-primary btn-block mt-16" id="edit-submit">发布</button>
    </div>
  `;

  const titleInput = container.querySelector('#edit-title');
  const contentInput = container.querySelector('#edit-content');
  const imageList = container.querySelector('#image-list');
  const addBtn = container.querySelector('#image-add-btn');
  const fileInput = container.querySelector('#image-input');

  container.querySelector('#edit-back').addEventListener('click', () => window.history.back());

  addBtn.addEventListener('click', () => fileInput.click());

  fileInput.addEventListener('change', async () => {
    const files = Array.from(fileInput.files);
    if (selectedFiles.length + files.length > 9) {
      toast('最多上传9张图片');
      fileInput.value = '';
      return;
    }

    for (const file of files) {
      if (selectedFiles.length >= 9) break;
      try {
        const formData = new FormData();
        formData.append('file', file);
        const name = await post('/upload/note', formData);
        selectedFiles.push(name);
        renderImageList();
      } catch (e) {
        toast(e.message || '上传失败');
      }
    }
    fileInput.value = '';
  });

  function renderImageList() {
    const existing = imageList.querySelectorAll('.image-upload-item');
    existing.forEach(el => el.remove());

    selectedFiles.forEach((name, index) => {
      const item = document.createElement('div');
      item.className = 'image-upload-item';
      const url = imgUrl(name);
      item.innerHTML = `
        <img src="${url}" alt="" onerror="imgError(event)"/>
        <button class="img-remove" data-index="${index}">×</button>
      `;
      item.querySelector('.img-remove').addEventListener('click', async () => {
        // Try to delete from server, but remove locally either way
        try { await get('/upload/note/delete?name=' + encodeURIComponent(name)); } catch {}
        selectedFiles.splice(index, 1);
        renderImageList();
      });
      imageList.insertBefore(item, addBtn);
    });
  }

  // Submit
  container.querySelector('#edit-submit').addEventListener('click', async () => {
    const title = titleInput.value.trim();
    const content = contentInput.value.trim();
    if (!title) { toast('请输入标题'); return; }
    if (!content) { toast('请输入正文'); return; }

    const btn = container.querySelector('#edit-submit');
    btn.disabled = true;
    btn.textContent = '发布中...';

    try {
      const imagesStr = selectedFiles.join(',');
      const noteId = await post('/note', { title, content, images: imagesStr });
      toast('发布成功');
      window.location.hash = '#/note/' + noteId;
    } catch {
      btn.disabled = false;
      btn.textContent = '发布';
    }
  });

  return () => {};
}
