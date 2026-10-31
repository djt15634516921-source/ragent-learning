package com.dujiteng.ragentlearning.knowledge.vo;

import org.springframework.core.io.Resource;

public record DocumentDownload(DocumentVO document, Resource resource) { }
