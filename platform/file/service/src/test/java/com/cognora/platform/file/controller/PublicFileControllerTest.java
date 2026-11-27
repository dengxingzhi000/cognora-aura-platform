package com.cognora.platform.file.controller;

import com.cognora.platform.file.entity.FileMetadata;
import com.cognora.platform.file.service.FileNotFoundException;
import com.cognora.platform.file.service.download.FileDownloadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicFileControllerTest {

    @Mock
    private FileDownloadService fileDownloadService;

    @InjectMocks
    private PublicFileController controller;

    @Test
    void avatarStreamsPublicAvatarWithCacheHeaders() throws Exception {
        FileMetadata metadata = new FileMetadata();
        metadata.setId("file-1");
        metadata.setBizType("AVATAR");
        metadata.setContentType("image/png");
        metadata.setOriginalName("avatar.png");
        metadata.setFileSize(4L);
        when(fileDownloadService.resolve("file-1")).thenReturn(metadata);
        when(fileDownloadService.openContent("file-1"))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.avatar("file-1", response);

        assertEquals(200, response.getStatus());
        assertEquals("image/png", response.getContentType());
        assertEquals("public, max-age=86400", response.getHeader(HttpHeaders.CACHE_CONTROL));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, response.getContentAsByteArray());
    }

    @Test
    void avatarHidesFilesThatAreNotPublicAvatars() throws Exception {
        FileMetadata metadata = new FileMetadata();
        metadata.setId("file-1");
        metadata.setBizType("CONTRACT");
        metadata.setContentType("application/pdf");
        when(fileDownloadService.resolve("file-1")).thenReturn(metadata);

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.avatar("file-1", response);

        assertEquals(404, response.getStatus());
        verify(fileDownloadService, never()).openContent(anyString());
    }

    @Test
    void avatarReturns404WhenFileIsMissing() throws Exception {
        when(fileDownloadService.resolve("missing")).thenThrow(new FileNotFoundException("missing"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.avatar("missing", response);

        assertEquals(404, response.getStatus());
        verify(fileDownloadService, never()).openContent(anyString());
    }
}