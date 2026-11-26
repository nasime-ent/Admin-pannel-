/**
 * KICHS SCHOOL RESOURCES - Google Apps Script
 *
 * Uses the existing KICHS Google Drive Resources root folder.
 * Supports:
 *   GET  ?action=list&path=...&callback=...
 *   GET  ?action=downloadinfo&id=...&callback=...
 *   GET  ?action=downloadchunk&id=...&start=...&end=...&callback=...
 *   POST JSON {fileName,mimeType,path,folderId,fileBase64,sender}
 *
 * IMPORTANT:
 * 1. Keep the existing deployment URL.
 * 2. Set ROOT_FOLDER_ID to the existing Resources folder ID.
 * 3. This script creates missing folders only; it does not delete or rename folders.
 * 4. Uploads require a valid selected semester/subject path; empty paths are rejected.
 */

const ROOT_FOLDER_ID = '10VlZFBqntyAuP-GL6Dl3Bl6gzqTLNgyu';
const MAX_UPLOAD_BYTES = 20 * 1024 * 1024; // 20 MB
const DOWNLOAD_CHUNK_BYTES = 256 * 1024;  // 256 KB

/**
 * Folder names below deliberately match the path format used by the current
 * KICHS HTML uploader: "YEAR 2 - SEMESTER 1/Nurses/Course Unit".
 *
 * Year 1 stays shared (no Nurses/Midwives subfolders).
 * Year 3 has Semester 1 only.
 */
const KICHS_FOLDER_STRUCTURE = {
  'YEAR 1 - SEMESTER 1': [
    'Anatomy & First Aid',
    'Microbiology & PCH',
    'Foundation 1 & Computer'
  ],
  'YEAR 1 - SEMESTER 2': [
    'Anatomy 2',
    'PHC',
    'Foundation 2 & Socio-Psychology'
  ],
  'YEAR 2 - SEMESTER 1': {
    'Nurses': [
      'Paediatric & Palliative Care',
      'Gynaecology & Surgery',
      'Medicine & Pharmacology'
    ],
    'Midwives': [
      'Obstetrics Anatomy',
      'Normal Delivery',
      'Palliative Care',
      'Pediatrics',
      'Pharmacology'
    ]
  },
  'YEAR 2 - SEMESTER 2': {
    'Nurses': [],
    'Midwives': []
  },
  'YEAR 3 - SEMESTER 1': {
    'Nurses': [],
    'Midwives': []
  }
};

function doGet(e) {
  const p = (e && e.parameter) || {};
  const action = String(p.action || 'list').trim().toLowerCase();
  let result;

  try {
    if (action === 'list') {
      result = listResources_(p.path || '');
    } else if (action === 'downloadinfo') {
      result = getDownloadInfo_(p.id);
    } else if (action === 'downloadchunk') {
      result = getDownloadChunk_(p.id, p.start, p.end);
    } else if (action === 'listall' || action === 'list_all') {
      result = listAllResources_();
    } else if (action === 'uploadstatus') {
      result = getUploadStatus_(p.id);
    } else if (action === 'createfolders') {
      result = createKichsFolders();
    } else if (action === 'test' || action === 'ping' || action === 'status') {
      const root = getRootFolder_();
      result = {
        success: true,
        message: 'KICHS Resources server is connected.',
        rootFolder: root.getName(),
        supportedActions: ['list', 'listall', 'downloadinfo', 'downloadchunk', 'uploadstatus', 'createfolders', 'test']
      };
    } else if (action === 'resources' || action === 'listresources' || action === 'getresources') {
      result = listResources_(p.path || '');
    } else {
      result = {
        success: false,
        message: 'Unknown server action: ' + action,
        supportedActions: ['list', 'listall', 'downloadinfo', 'downloadchunk', 'uploadstatus', 'createfolders', 'test']
      };
    }
  } catch (err) {
    result = { success: false, message: safeMessage_(err) };
  }

  return output_(result, p.callback);
}

function doPost(e) {
  Logger.log('========== KICHS UPLOAD STARTED ==========');
  let requestId = '';

  try {
    const raw = e && e.postData && e.postData.contents;
    Logger.log('Request received: ' + Boolean(raw));
    if (!raw) throw new Error('No upload data received.');

    const data = JSON.parse(raw);
    requestId = String(data.requestId || '').trim();
    const fileName = cleanName_(data.fileName || 'resource');
    const mimeType = String(data.mimeType || 'application/octet-stream');
    const base64 = String(data.fileBase64 || '');
    const path = normalizeUploadPath_(data.path || data.folderPath || '');
    const suppliedFolderId = String(data.folderId || '').trim();
    const sender = data.sender || {
      name: data.uploader || 'KICHS Admin',
      contact: data.contact || '',
      set: data.set || ''
    };

    Logger.log('File name: ' + fileName);
    Logger.log('MIME type: ' + mimeType);
    Logger.log('Selected path: ' + path);
    Logger.log('Supplied folder ID: ' + (suppliedFolderId || '(not supplied)'));
    Logger.log('Sender details: ' + JSON.stringify(sender));
    Logger.log('Base64 character count: ' + base64.length);

    if (!base64) throw new Error('The uploaded file is empty.');
    if (!path) {
      throw new Error('No destination was selected. Choose a semester and subject folder before uploading.');
    }

    if (suppliedFolderId && suppliedFolderId !== ROOT_FOLDER_ID) {
      throw new Error('Destination folder ID does not match the configured KICHS resources folder. Check the HTML folder ID.');
    }

    validateResourcePath_(path);

    const bytes = Utilities.base64Decode(base64);
    Logger.log('Decoded file size: ' + bytes.length + ' bytes');
    if (bytes.length > MAX_UPLOAD_BYTES) {
      throw new Error('File exceeds the 20 MB upload limit.');
    }

    const root = getRootFolder_();
    const targetFolder = findPath_(root, path);
    if (!targetFolder) {
      throw new Error('The selected destination folder no longer exists. Refresh the folder choices and try again.');
    }

    Logger.log('Drive root folder: ' + root.getName());
    Logger.log('Destination folder: ' + targetFolder.getName());
    Logger.log('Destination folder ID: ' + targetFolder.getId());

    const blob = Utilities.newBlob(bytes, mimeType, fileName);
    const file = targetFolder.createFile(blob);

    const senderLines = [
      'Uploaded through KICHS School Resources',
      sender.name ? 'Name: ' + String(sender.name) : '',
      sender.contact ? 'Contact: ' + String(sender.contact) : '',
      sender.set ? 'Set: ' + String(sender.set) : ''
    ].filter(Boolean);

    try {
      file.setDescription(senderLines.join('\n'));
    } catch (descriptionError) {
      Logger.log('WARNING: Could not save uploader description: ' + safeMessage_(descriptionError));
    }

    const result = {
      success: true,
      message: 'Upload successful.',
      id: file.getId(),
      name: file.getName(),
      size: file.getSize(),
      mimeType: file.getMimeType(),
      path: path,
      destinationFolder: targetFolder.getName(),
      destinationFolderId: targetFolder.getId()
    };

    Logger.log('UPLOAD SUCCESSFUL');
    Logger.log('Saved file name: ' + result.name);
    Logger.log('Saved file ID: ' + result.id);
    Logger.log('Saved file size: ' + result.size);
    Logger.log('Saved destination path: ' + result.path);
    Logger.log('========== KICHS UPLOAD FINISHED ==========');
    saveUploadStatus_(requestId, result);

    return output_(result);

  } catch (err) {
    Logger.log('UPLOAD FAILED: ' + safeMessage_(err));
    if (err && err.stack) Logger.log(err.stack);
    Logger.log('========== KICHS UPLOAD STOPPED ==========');
    const failure = { success: false, message: safeMessage_(err) };
    saveUploadStatus_(requestId, failure);
    return output_(failure);
  }
}

/**
 * Validate a selected path against the real folder hierarchy in the configured
 * KICHS Resources root. Year 1 uses semester/course-unit; Years 2 and 3 use
 * semester/programme/course-unit. No folders are silently created by uploads.
 */
function validateResourcePath_(path) {
  const parts = normalizePath_(path);
  if (parts.length < 2) throw new Error('Select a complete semester and destination folder.');

  const semester = parts[0];
  if (!Object.prototype.hasOwnProperty.call(KICHS_FOLDER_STRUCTURE, semester)) {
    throw new Error('Unknown semester folder: ' + semester);
  }

  const isYear1 = semester.indexOf('YEAR 1 - ') === 0;
  if (isYear1 && parts.length !== 2) {
    throw new Error('For Year 1, select a course unit folder.');
  }
  if (!isYear1 && parts.length !== 3) {
    throw new Error('For Years 2 and 3, select a programme and course unit folder.');
  }

  const folder = findPath_(getRootFolder_(), parts.join('/'));
  if (!folder) throw new Error('The selected Drive folder does not exist. Refresh the folder choices and try again.');
}

function normalizeUploadPath_(path) {
  const parts = normalizePath_(path);
  while (parts.length && (parts[0].toUpperCase() === 'KIRYOKYA-APP' || parts[0].toUpperCase() === '01-APK')) {
    parts.shift();
  }
  return parts.join('/');
}

function saveUploadStatus_(requestId, result) {
  if (!requestId) return;
  try {
    PropertiesService.getScriptProperties().setProperty('KICHS_UPLOAD_' + requestId, JSON.stringify(result));
  } catch (err) {
    Logger.log('Could not save upload status: ' + safeMessage_(err));
  }
}

function getUploadStatus_(requestId) {
  const id = String(requestId || '').trim();
  if (!id) throw new Error('Missing upload request ID.');
  const raw = PropertiesService.getScriptProperties().getProperty('KICHS_UPLOAD_' + id);
  if (!raw) return { success: true, pending: true, message: 'Upload is still processing.' };
  try { return JSON.parse(raw); }
  catch (err) { return { success: false, pending: false, message: 'Stored upload status is unreadable.' }; }
}

/** Creates every folder in the defined structure, without duplicating existing folders. */
function createKichsFolders() {
  const root = getRootFolder_();
  const created = [];
  const existing = [];

  Object.keys(KICHS_FOLDER_STRUCTURE).forEach(function(semesterName) {
    const semesterFolder = getOrCreateChild_(root, semesterName, created, existing);
    const semesterContents = KICHS_FOLDER_STRUCTURE[semesterName];

    if (Array.isArray(semesterContents)) {
      semesterContents.forEach(function(courseName) {
        getOrCreateChild_(semesterFolder, courseName, created, existing);
      });
    } else {
      Object.keys(semesterContents).forEach(function(programmeName) {
        const programmeFolder = getOrCreateChild_(semesterFolder, programmeName, created, existing);
        semesterContents[programmeName].forEach(function(courseName) {
          getOrCreateChild_(programmeFolder, courseName, created, existing);
        });
      });
    }
  });

  return {
    success: true,
    message: 'KICHS resource folders are ready. Existing folders were kept.',
    createdCount: created.length,
    existingCount: existing.length,
    created: created,
    rootFolder: root.getName()
  };
}

/** Returns files directly inside the requested resource folder. */
function listResources_(path) {
  const root = getRootFolder_();
  const folder = findPath_(root, path);
  if (!folder) {
    return {
      success: true,
      files: [],
      folders: [],
      courseUnit: lastPathPart_(path),
      message: 'Folder has not been created yet.'
    };
  }

  const folders = [];
  const folderIterator = folder.getFolders();
  while (folderIterator.hasNext()) {
    const childFolder = folderIterator.next();
    folders.push({ id: childFolder.getId(), name: childFolder.getName() });
  }
  folders.sort(function(a, b) {
    return a.name.toLowerCase().localeCompare(b.name.toLowerCase());
  });

  const files = [];
  const iterator = folder.getFiles();
  while (iterator.hasNext()) {
    const file = iterator.next();
    files.push({
      id: file.getId(),
      name: file.getName(),
      size: file.getSize(),
      mimeType: file.getMimeType(),
      updated: Utilities.formatDate(file.getLastUpdated(), Session.getScriptTimeZone(), 'dd MMM yyyy')
    });
  }

  files.sort(function(a, b) {
    return a.name.toLowerCase().localeCompare(b.name.toLowerCase());
  });

  return {
    success: true,
    folders: folders,
    files: files,
    courseUnit: folder.getName()
  };
}

/**
 * Lists all resource files recursively for the Admin Panel.
 */
function listAllResources_() {
  const root = getRootFolder_();
  const files = [];
  collectFilesRecursively_(root, '', files);

  files.sort(function(a, b) {
    return b.updatedTimestamp - a.updatedTimestamp;
  });

  files.forEach(function(item) {
    delete item.updatedTimestamp;
  });

  return {
    success: true,
    files: files,
    count: files.length
  };
}

function collectFilesRecursively_(folder, relativePath, output) {
  const fileIterator = folder.getFiles();

  while (fileIterator.hasNext()) {
    const file = fileIterator.next();
    let uploader = '';

    try {
      const description = file.getDescription() || '';
      const match = description.match(/(?:^|\n)Name:\s*(.+)/i);
      if (match) uploader = match[1].trim();
    } catch (ignore) {}

    const updated = file.getLastUpdated();

    output.push({
      id: file.getId(),
      name: file.getName(),
      size: file.getSize(),
      mimeType: file.getMimeType(),
      category: relativePath || folder.getName(),
      path: relativePath,
      uploader: uploader || 'Not recorded',
      updated: Utilities.formatDate(
        updated,
        Session.getScriptTimeZone(),
        'dd MMM yyyy'
      ),
      updatedTimestamp: updated.getTime()
    });
  }

  const folderIterator = folder.getFolders();
  while (folderIterator.hasNext()) {
    const child = folderIterator.next();
    const childPath = relativePath
      ? relativePath + '/' + child.getName()
      : child.getName();

    collectFilesRecursively_(child, childPath, output);
  }
}

function getDownloadInfo_(fileId) {
  if (!fileId) throw new Error('Missing file ID.');
  const file = DriveApp.getFileById(String(fileId));
  return {
    success: true,
    id: file.getId(),
    name: file.getName(),
    size: file.getSize(),
    mimeType: file.getMimeType(),
    chunkSize: DOWNLOAD_CHUNK_BYTES
  };
}

function getDownloadChunk_(fileId, startValue, endValue) {
  if (!fileId) throw new Error('Missing file ID.');
  const start = Math.max(0, parseInt(startValue, 10) || 0);
  const requestedEnd = parseInt(endValue, 10);
  const file = DriveApp.getFileById(String(fileId));
  const bytes = file.getBlob().getBytes();
  if (start >= bytes.length) throw new Error('Requested download position is outside the file.');

  const maxEnd = Math.min(bytes.length - 1, start + DOWNLOAD_CHUNK_BYTES - 1);
  const end = isFinite(requestedEnd) ? Math.min(maxEnd, Math.max(start, requestedEnd)) : maxEnd;
  const part = bytes.slice(start, end + 1);

  return {
    success: true,
    base64: Utilities.base64Encode(part),
    start: start,
    end: end,
    nextOffset: end + 1
  };
}

/* ------------------------- Drive folder helpers ------------------------- */

function getRootFolder_() {
  return DriveApp.getFolderById(ROOT_FOLDER_ID);
}

function getOrCreatePath_(root, path) {
  const parts = normalizePath_(path);
  let current = root;
  parts.forEach(function(part) {
    current = getOrCreateChild_(current, part);
  });
  return current;
}

function findPath_(root, path) {
  const parts = normalizePath_(path);
  let current = root;
  for (let i = 0; i < parts.length; i++) {
    const matches = current.getFoldersByName(parts[i]);
    if (!matches.hasNext()) return null;
    current = matches.next();
  }
  return current;
}

function getOrCreateChild_(parent, name, created, existing) {
  const safe = cleanName_(name);
  const matches = parent.getFoldersByName(safe);
  if (matches.hasNext()) {
    if (existing) existing.push(safe);
    return matches.next();
  }
  const folder = parent.createFolder(safe);
  if (created) created.push(safe);
  return folder;
}

function normalizePath_(path) {
  return String(path || '')
    .split('/')
    .map(function(part) { return cleanName_(part.trim()); })
    .filter(function(part) { return part && part !== '.' && part !== '..'; });
}

function cleanName_(value) {
  return String(value || '')
    .replace(/[\\\/:*?"<>|]/g, '-')
    .replace(/\s+/g, ' ')
    .trim()
    .substring(0, 180);
}

function lastPathPart_(path) {
  const parts = normalizePath_(path);
  return parts.length ? parts[parts.length - 1] : '';
}

/* ------------------------------ Response ------------------------------- */

function output_(data, callback) {
  const json = JSON.stringify(data);
  if (callback) {
    const cb = String(callback);
    // Only permit a simple JavaScript callback identifier.
    if (!/^[A-Za-z_$][0-9A-Za-z_$]*$/.test(cb)) {
      return ContentService.createTextOutput(JSON.stringify({
        success: false,
        message: 'Invalid callback name.'
      })).setMimeType(ContentService.MimeType.JSON);
    }
    return ContentService.createTextOutput(cb + '(' + json + ');')
      .setMimeType(ContentService.MimeType.JAVASCRIPT);
  }
  return ContentService.createTextOutput(json)
    .setMimeType(ContentService.MimeType.JSON);
}

function safeMessage_(err) {
  return err && err.message ? String(err.message) : 'An unexpected server error occurred.';
}