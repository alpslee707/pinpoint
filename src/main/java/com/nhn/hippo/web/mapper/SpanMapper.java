package com.nhn.hippo.web.mapper;

import com.profiler.common.bo.AnnotationBo;
import com.profiler.common.bo.SpanBo;
import com.profiler.common.bo.SpanEventBo;
import com.profiler.common.hbase.HBaseTables;
import com.profiler.common.util.BytesUtils;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.hadoop.hbase.RowMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 *
 */
@Component
public class SpanMapper implements RowMapper<List<SpanBo>> {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private AnnotationMapper annotationMapper;

    public AnnotationMapper getAnnotationMapper() {
        return annotationMapper;
    }

    public void setAnnotationMapper(AnnotationMapper annotationMapper) {
        this.annotationMapper = annotationMapper;
    }

    @Override
    public List<SpanBo> mapRow(Result result, int rowNum) throws Exception {
        byte[] rowKey = result.getRow();

        if (rowKey == null) {
            return Collections.emptyList();
        }

        long most = BytesUtils.bytesToFirstLong(rowKey);
        long least = BytesUtils.bytesToSecondLong(rowKey);

        KeyValue[] keyList = result.raw();
        List<SpanBo> spanList = new ArrayList<SpanBo>();
        Map<Integer, SpanBo> spanMap = new HashMap<Integer, SpanBo>();
        List<SpanEventBo> spanEventBoList = new ArrayList<SpanEventBo>();
        for (KeyValue kv : keyList) {
            // family name "span"일때로만 한정.
            if (kv.getFamilyLength() == HBaseTables.TRACES_CF_SPAN.length) {
                SpanBo spanBo = new SpanBo();
                spanBo.setMostTraceId(most);
                spanBo.setLeastTraceId(least);
                spanBo.setCollectorAcceptTime(kv.getTimestamp());

                spanBo.setSpanID(Bytes.toInt(kv.getBuffer(), kv.getQualifierOffset()));
                spanBo.readValue(kv.getBuffer(), kv.getValueOffset());
                if (logger.isDebugEnabled()) {
                    logger.debug("read span :{}", spanBo);
                }
                spanList.add(spanBo);
                spanMap.put(spanBo.getSpanId(), spanBo);
            } else if (kv.getFamilyLength() == HBaseTables.TRACES_CF_TERMINALSPAN.length) {
                SpanEventBo spanEventBo = new SpanEventBo();
                spanEventBo.setMostTraceId(most);
                spanEventBo.setLeastTraceId(least);

                int spanId = Bytes.toInt(kv.getBuffer(), kv.getQualifierOffset());
                // 앞의 spanid가 int이므로 4.
                final int spanIdOffset = 4;
                short sequence = Bytes.toShort(kv.getBuffer(), kv.getQualifierOffset() + spanIdOffset);
                spanEventBo.setSpanId(spanId);
                spanEventBo.setSequence(sequence);

                spanEventBo.readValue(kv.getBuffer(), kv.getValueOffset());
                if (logger.isDebugEnabled()) {
                    logger.debug("read spanEvent :{}", spanEventBo);
                }
                spanEventBoList.add(spanEventBo);
            }
        }
        for (SpanEventBo spanEventBo : spanEventBoList) {
            SpanBo spanBo = spanMap.get(spanEventBo.getSpanId());
            if (spanBo != null) {
                spanBo.addSpanEvent(spanEventBo);
            }
        }
        if (annotationMapper != null) {
            Map<Integer, List<AnnotationBo>> annotationMap = annotationMapper.mapRow(result, rowNum);
            addAnnotation(spanList, annotationMap);
        }


        return spanList;

    }

    private void addAnnotation(List<SpanBo> spanList, Map<Integer, List<AnnotationBo>> annotationMap) {
        for (SpanBo bo : spanList) {
            int spanID = bo.getSpanId();
            List<AnnotationBo> anoList = annotationMap.get(spanID);
            bo.setAnnotationBoList(anoList);
        }
    }
}
