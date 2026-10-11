package com.example.capstone.note.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;
import com.example.capstone.global.validation.TextValidation;
import com.example.capstone.note.domain.Note;
import com.example.capstone.note.dto.request.NoteCreateRequest;
import com.example.capstone.note.dto.request.NoteUpdateRequest;
import com.example.capstone.note.dto.response.NoteListResponse;
import com.example.capstone.note.dto.response.NoteResponse;
import com.example.capstone.note.dto.response.NoteSummaryResponse;
import com.example.capstone.note.repository.NoteRepository;
import com.example.capstone.topic.service.TopicService;
import com.example.capstone.user.repository.AppUserRepository;

@Service
public class NoteService {
    private final NoteRepository notes;
    private final AppUserRepository users;
    private final TopicService topics;
    private final Clock clock;
    public NoteService(NoteRepository notes,AppUserRepository users,TopicService topics,Clock clock) {
        this.notes=notes; this.users=users; this.topics=topics; this.clock=clock;
    }
    @Transactional
    public NoteResponse create(UUID userId,NoteCreateRequest request) {
        String title=TextValidation.title(request.title(),"title","제목",200);
        String body=TextValidation.body(request.body()==null?"":request.body());
        lock(userId);
        if(request.topicId()!=null) { topics.owned(userId,request.topicId()); }
        var note=new Note(userId,request.topicId(),title,body,clock.instant());
        checkTitle(userId,note.getTopicId(),title,note.getId());
        note=save(note);
        return NoteResponse.from(note);
    }
    @Transactional
    public NoteResponse update(UUID userId,UUID id,NoteUpdateRequest request) {
        String title=TextValidation.title(request.title(),"title","제목",200);
        String body=TextValidation.body(request.body());
        lock(userId);
        Note note=owned(userId,id,true);
        if(note.getVersion()!=request.version()) {
            throw new ApiException(ErrorCode.NOTE_CONFLICT,"다른 곳에서 이 노트가 먼저 수정되었습니다.",Map.of("current",NoteResponse.from(note)));
        }
        checkTitle(userId,note.getTopicId(),title,id);
        note.edit(title,body,clock.instant());
        return NoteResponse.from(save(note));
    }
    @Transactional
    public NoteResponse move(UUID userId,UUID id,UUID destination) {
        lock(userId);
        Note note=owned(userId,id,false);
        if(destination!=null) { topics.owned(userId,destination); }
        if(notes.titleExists(userId,destination,note.getTitle(),id)) { throw moveDuplicate(note.getTitle()); }
        try {
            if(notes.move(userId,id,destination,clock.instant())!=1) {
                throw new ApiException(ErrorCode.NOT_FOUND,"삭제된 노트입니다");
            }
        } catch(DataIntegrityViolationException exception) {
            for(Throwable cause=exception;cause!=null;cause=cause.getCause()) {
                if(cause instanceof ConstraintViolationException constraint && "note_topic_title_unique".equals(constraint.getConstraintName())) {
                    throw moveDuplicate(note.getTitle());
                }
            }
            throw exception;
        }
        return NoteResponse.from(owned(userId,id,false));
    }
    private ApiException moveDuplicate(String title) {
        return new ApiException(ErrorCode.NOTE_TITLE_TAKEN,"옮길 주제에 같은 제목의 노트가 있어 옮기지 못했습니다.",Map.of("titles",List.of(title)));
    }
    @Transactional
    public void delete(UUID userId,UUID id) {
        lock(userId);
        Note note=notes.lockForDelete(userId,id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,"삭제된 노트입니다"));
        if(notes.hasEvidence(userId,id)) {
            throw new ApiException(ErrorCode.NOTE_DELETE_BLOCKED,"근거가 연결된 노트는 아직 삭제할 수 없습니다.");
        }
        notes.delete(note); notes.flush();
    }
    @Transactional(readOnly=true)
    public NoteResponse get(UUID userId,UUID id) { return NoteResponse.from(owned(userId,id,false)); }
    @Transactional(readOnly=true)
    public NoteListResponse list(UUID userId,String filter,String sort,String q) {
        String query=q==null?"":q.trim();
        if(query.codePointCount(0,query.length())>100) {
            throw TextValidation.invalid("q","too_long","검색어는 100자 이하로 입력하세요.");
        }
        boolean all=filter==null,unassigned="none".equals(filter);
        UUID topicId=null;
        if(!all && !unassigned) {
            try {
                topicId=UUID.fromString(filter);
                if(!topicId.toString().equalsIgnoreCase(filter)) { throw new IllegalArgumentException(); }
            } catch(IllegalArgumentException exception) { throw TextValidation.invalid("topicId","invalid","주제 ID가 올바르지 않습니다."); }
            topics.owned(userId,topicId);
        }
        if(!"title".equals(sort) && !"updated".equals(sort)) {
            throw TextValidation.invalid("sort","invalid","정렬 기준이 올바르지 않습니다.");
        }
        String pattern=query.isEmpty()?null:"%"+escapeLike(query)+"%";
        return new NoteListResponse(notes.list(userId,all,unassigned,topicId,pattern,sort).stream()
                .map(note -> summary(note,query)).toList());
    }
    private String escapeLike(String query) {
        return query.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
    }
    @Transactional(readOnly=true)
    public void requireOwned(UUID userId,UUID id) {
        if(!notes.existsByIdAndUserId(id,userId)) {
            throw new ApiException(ErrorCode.NOT_FOUND,"삭제된 노트입니다");
        }
    }
    private Note owned(UUID userId,UUID id,boolean saving) {
        return notes.findByIdAndUserId(id,userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                saving?"다른 곳에서 삭제된 노트입니다":"삭제된 노트입니다"));
    }
    private void lock(UUID userId) {
        users.lockById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED,"로그인이 필요합니다."));
    }
    private void checkTitle(UUID userId,UUID topicId,String title,UUID except) {
        if(notes.titleExists(userId,topicId,title,except)) { throw duplicate(topicId,title); }
    }
    private Note save(Note note) {
        try { return notes.saveAndFlush(note); }
        catch(DataIntegrityViolationException exception) {
            for(Throwable cause=exception;cause!=null;cause=cause.getCause()) {
                if(cause instanceof ConstraintViolationException constraint && "note_topic_title_unique".equals(constraint.getConstraintName())) {
                    throw duplicate(note.getTopicId(),note.getTitle());
                }
            }
            throw exception;
        }
    }
    private ApiException duplicate(UUID topicId,String title) {
        return new ApiException(ErrorCode.NOTE_TITLE_TAKEN,topicId==null?"미분류에 같은 제목의 노트가 있습니다.":"같은 주제에 같은 제목의 노트가 있습니다.",Map.of("titles",List.of(title)));
    }
    private NoteSummaryResponse summary(Note note,String query) {
        String body=note.getBody();
        int total=body.codePointCount(0,body.length());
        int start=0;
        if(!query.isEmpty()) {
            var match=Pattern.compile(Pattern.quote(query),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(body);
            if(match.find()) {
                start=Math.max(0,body.codePointCount(0,match.start())-20);
                start=Math.min(start,Math.max(0,total-80));
            }
        }
        int end=Math.min(total,start+80);
        String snippet=body.substring(body.offsetByCodePoints(0,start),body.offsetByCodePoints(0,end));
        return new NoteSummaryResponse(note.getId(),note.getTopicId(),note.getTitle(),snippet,note.getVersion(),note.getUpdatedAt());
    }
}
