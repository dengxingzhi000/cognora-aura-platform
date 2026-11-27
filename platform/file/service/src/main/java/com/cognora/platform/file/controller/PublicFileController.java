package com.cognora.platform.file.controller;

import com.cognora.platform.common.log.util.LogUtils;
import com.cognora.platform.file.entity.FileMetadata;
import com.cognora.platform.file.service.FileNotFoundException;
import com.cognora.platform.file.service.download.FileDownloadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Anonymous read access for assets that are meant to be embedded by URL, such as
 * user avatars. Only files with {@code bizType=AVATAR} and an image content type
 * are exposed; every other file stays behind {@link FileController}'s guarded
 * preview/download endpoints.
 */
@RestController
@RequestMapping("/api/public/files")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "公开文件访问", description = "头像等公开图片的匿名读取接口。")
public class PublicFileController {

    private static final String AVATAR_BIZ_TYPE = "AVATAR";
    private static final String CACHE_CONTROL_VALUE = "public, max-age=86400";

    private final FileDownloadService fileDownloadService;

    @Operation(summary = "读取头像图片", description = "按文件 ID 输出头像图片，无需登录；仅开放 bizType=AVATAR 的图片文件。")
    @GetMapping("/{id}/avatar")
    public void avatar(@Parameter(description = "文件 ID") @PathVariable String id,
                       HttpServletResponse response) {
        try {
            FileMetadata metadata = fileDownloadService.resolve(id);
            if (!isPublicAvatar(metadata)) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            try (var content = fileDownloadService.openContent(id)) {
                response.setContentType(metadata.getContentType());
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.builder("inline")
                                .filename(encode(metadata.getOriginalName()))
                                .build()
                                .toString());
                response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE);
                if (metadata.getFileSize() != null) {
                    response.setContentLengthLong(metadata.getFileSize());
                }
                content.transferTo(response.getOutputStream());
            }
        } catch (FileNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        } catch (Exception e) {
            log.error("Failed to stream public avatar {}", LogUtils.sanitizeForLog(id), e);
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    private boolean isPublicAvatar(FileMetadata metadata) {
        String contentType = metadata.getContentType();
        return AVATAR_BIZ_TYPE.equals(metadata.getBizType())
                && contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    private String encode(String name) {
        return URLEncoder.encode(name == null ? "avatar" : name, StandardCharsets.UTF_8);
    }
}