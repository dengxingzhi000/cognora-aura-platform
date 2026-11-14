package com.cognora.platform.file.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cognora.platform.common.log.annotation.AuditLog;
import com.cognora.platform.common.log.util.LogUtils;
import com.cognora.platform.common.response.ApiResponse;
import com.cognora.platform.file.api.dto.FileBatchDeleteRequest;
import com.cognora.platform.file.api.dto.FileMetadataDTO;
import com.cognora.platform.file.api.dto.FileStatsDTO;
import com.cognora.platform.file.api.dto.FileUpdateRequest;
import com.cognora.platform.file.convert.FileMetadataConvert;
import com.cognora.platform.file.service.FileNotFoundException;
import com.cognora.platform.file.service.download.FileDownloadService;
import com.cognora.platform.file.service.management.FileManagementService;
import com.cognora.platform.file.service.metadata.FileMetadataService;
import com.cognora.platform.file.service.upload.FileValidationException;
import com.cognora.platform.file.service.upload.InstantUploadService;
import com.cognora.platform.file.service.upload.UploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "文件管理", description = "文件上传、秒传校验、访问地址生成与下载接口。")
public class FileController {

    private final UploadService uploadService;
    private final InstantUploadService instantUploadService;
    private final FileDownloadService fileDownloadService;
    private final FileMetadataConvert fileMetadataConvert;
    private final FileMetadataService fileMetadataService;
    private final FileManagementService fileManagementService;

    @Operation(summary = "分页查询文件", description = "按名称关键字、业务类型和业务 ID 分页查询当前租户的文件元数据。")
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('file:file:page')")
    public ApiResponse<Page<FileMetadataDTO>> page(
            @Parameter(description = "页码，默认 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页数量，默认 20") @RequestParam(required = false) Integer size,
            @Parameter(description = "文件名关键字") @RequestParam(required = false) String keyword,
            @Parameter(description = "业务类型") @RequestParam(required = false) String bizType,
            @Parameter(description = "业务 ID") @RequestParam(required = false) String bizId) {
        return ApiResponse.success(fileMetadataService.pageFiles(
                page == null ? 1 : page, size == null ? 20 : size, keyword, bizType, bizId));
    }

    @Operation(summary = "文件统计", description = "统计当前租户的文件总数、占用空间、类型分布与最近上传时间。")
    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('file:file:stats')")
    public ApiResponse<FileStatsDTO> stats() {
        return ApiResponse.success(fileMetadataService.getStats());
    }

    @Operation(summary = "更新文件信息", description = "重命名文件或调整业务类型、业务 ID 关联。传空表示解除关联。")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('file:file:update')")
    @AuditLog(operation = "更新文件信息", businessType = "FILE", riskLevel = 3)
    public ApiResponse<FileMetadataDTO> update(
            @Parameter(description = "文件 ID") @PathVariable String id,
            @RequestBody FileUpdateRequest request) {
        try {
            return ApiResponse.success(fileMetadataConvert.toDTO(fileManagementService.update(
                    id, request.getOriginalName(), request.getBizType(), request.getBizId())));
        } catch (FileNotFoundException e) {
            return ApiResponse.fail(404, e.getMessage());
        } catch (FileValidationException e) {
            return ApiResponse.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update file {}", LogUtils.sanitizeForLog(id), e);
            return ApiResponse.fail(500, "Update failed");
        }
    }

    @Operation(summary = "删除文件", description = "删除文件元数据与存储对象。")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('file:file:delete')")
    @AuditLog(operation = "删除文件", businessType = "FILE", riskLevel = 4)
    public ApiResponse<Void> delete(@Parameter(description = "文件 ID") @PathVariable String id) {
        try {
            fileManagementService.delete(id);
            return ApiResponse.success();
        } catch (FileNotFoundException e) {
            return ApiResponse.fail(404, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete file {}", LogUtils.sanitizeForLog(id), e);
            return ApiResponse.fail(500, "Delete failed");
        }
    }

    @Operation(summary = "批量删除文件", description = "按 ID 列表批量删除当前租户的文件，返回实际删除数量。")
    @PostMapping("/batch-delete")
    @PreAuthorize("hasAuthority('file:file:batch-delete')")
    @AuditLog(operation = "批量删除文件", businessType = "FILE", riskLevel = 4)
    public ApiResponse<Integer> batchDelete(@RequestBody FileBatchDeleteRequest request) {
        try {
            return ApiResponse.success(fileManagementService.deleteBatch(request.getIds()));
        } catch (Exception e) {
            log.error("Failed to delete files in batch", e);
            return ApiResponse.fail(500, "Batch delete failed");
        }
    }

    @Operation(summary = "上传文件", description = "上传文件并返回文件的元数据信息。")
    @PostMapping("/upload")
    @PreAuthorize("hasAuthority('file:file:upload')")
    @AuditLog(operation = "上传文件", businessType = "FILE", riskLevel = 3)
    public ApiResponse<FileMetadataDTO> upload(@RequestParam("file") MultipartFile file,
                                                @Parameter(description = "业务类型") @RequestParam(value = "bizType", required = false) String bizType,
                                                @Parameter(description = "业务 ID") @RequestParam(value = "bizId", required = false) String bizId) {
        try {
            FileMetadataDTO metadata = fileMetadataConvert.toDTO(uploadService.upload(
                    file,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    bizType,
                    bizId
            ));
            return ApiResponse.success(metadata);
        } catch (FileValidationException e) {
            return ApiResponse.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to upload file {}",
                    LogUtils.sanitizeForLog(file.getOriginalFilename()), e);
            return ApiResponse.fail(500, "Upload failed");
        }
    }

    @Operation(summary = "校验文件是否存在", description = "根据文件 MD5 校验当前租户下文件是否已存在，用于秒传。")
    @GetMapping("/check/{md5}")
    @PreAuthorize("hasAnyAuthority('file:file:download', 'file:file:check')")
}