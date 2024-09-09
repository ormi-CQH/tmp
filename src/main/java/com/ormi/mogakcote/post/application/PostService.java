package com.ormi.mogakcote.post.application;

import com.ormi.mogakcote.exception.user.UserInvalidException;
import com.ormi.mogakcote.post_activity.domain.View;
import com.ormi.mogakcote.post_activity.infrastructure.ViewRepository;
import com.ormi.mogakcote.user.domain.User;
import com.ormi.mogakcote.user.infrastructure.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ormi.mogakcote.auth.model.AuthUser;
import com.ormi.mogakcote.badge.application.UserBadgeService;
import com.ormi.mogakcote.common.dto.SuccessResponse;
import com.ormi.mogakcote.exception.auth.AuthInvalidException;
import com.ormi.mogakcote.exception.post.PostInvalidException;
import com.ormi.mogakcote.post.domain.Post;
import com.ormi.mogakcote.post.domain.PostFlag;
import com.ormi.mogakcote.post.domain.ReportFlag;
import com.ormi.mogakcote.post.dto.request.PostRequest;
import com.ormi.mogakcote.notice.infrastructure.NoticeRepository;
import com.ormi.mogakcote.post.dto.request.PostSearchRequest;
import com.ormi.mogakcote.post.dto.response.PostResponse;
import com.ormi.mogakcote.post.dto.response.PostSearchResponse;
import com.ormi.mogakcote.post.infrastructure.PostRepository;

import com.ormi.mogakcote.exception.dto.ErrorType;
import com.ormi.mogakcote.problem.domain.PostAlgorithm;
import com.ormi.mogakcote.problem.infrastructure.PostAlgorithmRepository;
import com.ormi.mogakcote.user.application.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PostService {

    private final PostRepository postRepository;
    private final PostAlgorithmRepository postAlgorithmRepository;
    private final UserService userService;
    private final NoticeRepository noticeRepository;
    private final UserBadgeService userBadgeService;
    private final UserRepository userRepository;
    private final ViewRepository viewRepository;

    @Transactional
    public PostResponse createPost(AuthUser user, PostRequest request) {
        Post savedPost = buildAndSavePost(user.getId(), request);

        Long algorithmId = savePostAlgorithm(savedPost.getId(), request.getAlgorithmId());

        boolean postExists =
                postRepository.existsPostByCreatedAt(
                        LocalDateTime.of(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1),
                                LocalTime.of(0, 0)));
        if (postExists) {
            userService.updateActivity(user.getId(), "increaseDay");
        } else {
            userService.updateActivity(user.getId(), "resetDay");
        }

        userBadgeService.makeUserBadge(user, "POST");

        return PostResponse.toResponse(
                savedPost.getId(),
                savedPost.getTitle(),
                savedPost.getContent(),
                savedPost.getPlatformId(),
                savedPost.getProblemNumber(),
                algorithmId,
                savedPost.getLanguageId(),
                savedPost.getCode(),
                savedPost.getPostFlag().isPublic(),
                savedPost.getReportFlag().isReportRequested(),
                savedPost.getViewCnt(),
                savedPost.getPostFlag().isBanned());
    }

    @Transactional(readOnly = true)
    public PostResponse getPost(AuthUser user, Long postId) {
        Post post = getPostById(postId);
        Long algorithmId = getAlgorithmId(postId);

        User findUser = getUserOrThrowIfNotExist(user);

        // 조회 여부에 따라 view 추가/삭제
        if (!viewRepository.existsByUserIdAndPostId(findUser.getId(), postId)) {
            buildAndSaveView(postId, findUser);
            post.incrementViewCount();
        } else {
          viewRepository.deleteByUserIdAndPostId(findUser.getId(), postId);
          post.decrementViewCount();
        }

        return PostResponse.toResponse(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                post.getPlatformId(),
                post.getProblemNumber(),
                algorithmId,
                post.getLanguageId(),
                post.getCode(),
                post.getPostFlag().isPublic(),
                post.getReportFlag().isReportRequested(),
                post.getViewCnt(),
                post.getPostFlag().isBanned());
    }

  @Transactional(readOnly = true)
  public List<PostResponse> getAllPosts() {
    List<Post> posts = postRepository.findAll();
    return posts.stream()
        .map(
            post ->
                PostResponse.toResponse(
                    post.getId(),
                    post.getTitle(),
                    post.getContent(),
                    post.getPlatformId(),
                    post.getProblemNumber(),
                    getAlgorithmId(post.getId()),
                    post.getLanguageId(),
                    post.getCode(),
                    post.getPostFlag().isPublic(),
                    post.getReportFlag().isReportRequested(),
                    post.getViewCnt(),
                    post.getPostFlag().isBanned()))
        .collect(Collectors.toList());
  }

  @Transactional
  public PostResponse updatePost(AuthUser user, Long postId, PostRequest request) {
    Post post = getPostById(postId);
    validateSameUser(post.getUserId(), user.getId());

    post.update(
        request.getTitle(),
        request.getContent(),
        request.getPlatformId(),
        request.getLanguageId(),
        request.getProblemNumber());

    Post updatedPost = postRepository.save(post);

    Long algorithmId = updatePostAlgorithm(postId, request.getAlgorithmId());

    return PostResponse.toResponse(
        updatedPost.getId(),
        updatedPost.getTitle(),
        updatedPost.getContent(),
        updatedPost.getPlatformId(),
        updatedPost.getProblemNumber(),
        algorithmId,
        updatedPost.getLanguageId(),
        updatedPost.getCode(),
        updatedPost.getPostFlag().isPublic(),
        updatedPost.getReportFlag().isReportRequested(),
        updatedPost.getViewCnt(),
        updatedPost.getPostFlag().isBanned());
  }

  @Transactional
  public SuccessResponse deletePost(AuthUser user, Long postId) {
    Post post = getPostById(postId);

    validateSameUser(post.getUserId(), user.getId());

    postAlgorithmRepository.deleteByPostId(postId);
    postRepository.deleteById(postId);

    userService.updateActivity(user.getId(), "decreaseDay", post.getCreatedAt());

    return new SuccessResponse("게시글 삭제 성공");
  }

  private Post buildAndSavePost(Long userId, PostRequest request) {
    log.info("userId = {}", userId);
    PostFlag postFlag =
        PostFlag.builder().isPublic(request.isPublic()).isSuccess(false).isBanned(false).build();
    ReportFlag reportFlag =
        ReportFlag.builder()
            .isReportRequested(request.isReportRequested())
            .hasPreviousReportRequested(false)
            .build();
    Post post =
        Post.builder()
            .title(request.getTitle())
            .content(request.getContent())
            .platformId(request.getPlatformId())
            .problemNumber(request.getProblemNumber())
            .languageId(request.getLanguageId())
            .code(request.getCode())
            .postFlag(postFlag)
            .reportFlag(reportFlag)
            .viewCnt(0)
            .voteCnt(0)
            .userId(userId)
            .build();
    return postRepository.save(post);
  }

  private Post getPostById(Long postId) {
    return postRepository
        .findById(postId)
        .orElseThrow(() -> new PostInvalidException(ErrorType.POST_NOT_FOUND_ERROR));
  }

  private static void validateSameUser(Long postUserId, Long userId) {
    if (!postUserId.equals(userId)) {
      throw new AuthInvalidException(ErrorType.NON_IDENTICAL_USER_ERROR);
    }
  }
    

    @Transactional
    public PostResponse convertBanned(Long id) {
        Post findPost = getPostById(id);

        if (findPost.getPostFlag().isBanned()) {
            findPost.updateBanned(false);
        } else {
            findPost.updateBanned(true);
        }

        return PostResponse.toResponse(
                findPost.getId(),
                findPost.getTitle(),
                findPost.getContent(),
                findPost.getPlatformId(),
                findPost.getProblemNumber(),
                getAlgorithmId(id),
                findPost.getLanguageId(),
                findPost.getCode(),
                findPost.getPostFlag().isPublic(),
                findPost.getReportFlag().isReportRequested(),
                findPost.getViewCnt(),
                findPost.getPostFlag().isBanned()
        );
    }

    private Post buildAndSavePost(Long userId, PostRequest request) {
        log.info("userId = {}", userId);
        PostFlag postFlag =
                PostFlag.builder().isPublic(request.isPublic()).isSuccess(false).isBanned(false)
                        .build();
        ReportFlag reportFlag =
                ReportFlag.builder()
                        .isReportRequested(request.isReportRequested())
                        .hasPreviousReportRequested(false)
                        .build();
        Post post =
                Post.builder()
                        .title(request.getTitle())
                        .content(request.getContent())
                        .platformId(request.getPlatformId())
                        .problemNumber(request.getProblemNumber())
                        .languageId(request.getLanguageId())
                        .code(request.getCode())
                        .postFlag(postFlag)
                        .reportFlag(reportFlag)
                        .viewCnt(0)
                        .voteCnt(0)
                        .userId(userId)
                        .build();
        return postRepository.save(post);
    }

    private void buildAndSaveView(Long postId, User findUser) {
        View view = View.builder()
                .userId(findUser.getId())
                .postId(postId)
                .build();
        viewRepository.save(view);
    }

    private Post getPostById(Long postId) {
        return postRepository
                .findById(postId)
                .orElseThrow(() -> new PostInvalidException(ErrorType.POST_NOT_FOUND_ERROR));
    }

    private static void validateSameUser(Long postUserId, Long userId) {
        if (!postUserId.equals(userId)) {
            throw new AuthInvalidException(ErrorType.NON_IDENTICAL_USER_ERROR);
        }
    }

    private Long savePostAlgorithm(Long postId, Long algorithmId) {
        PostAlgorithm postAlgorithm =
                PostAlgorithm.builder().postId(postId).algorithmId(algorithmId).build();
        return postAlgorithmRepository.save(postAlgorithm).getAlgorithmId();
    }

    private Long getAlgorithmId(Long postId) {
        return postAlgorithmRepository.findByPostId(postId).getAlgorithmId();
    }

    private Long updatePostAlgorithm(Long postId, Long newAlgorithmId) {
        postAlgorithmRepository.deleteByPostId(postId);
        return savePostAlgorithm(postId, newAlgorithmId);
    }

    private User getUserOrThrowIfNotExist(AuthUser user) {
        return userRepository.findById(user.getId()).orElseThrow(
                () -> new UserInvalidException(ErrorType.USER_NOT_FOUND_ERROR)
        );
    }

}
