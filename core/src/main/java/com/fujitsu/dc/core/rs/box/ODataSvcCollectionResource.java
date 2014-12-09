/**
 * personium.io
 * Copyright 2014 FUJITSU LIMITED
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fujitsu.dc.core.rs.box;

import java.io.Reader;

import javax.ws.rs.DELETE;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.Path;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import org.apache.wink.webdav.WebDAVMethod;

import com.fujitsu.dc.common.utils.DcCoreUtils;
import com.fujitsu.dc.core.DcCoreException;
import com.fujitsu.dc.core.annotations.ACL;
import com.fujitsu.dc.core.auth.AccessContext;
import com.fujitsu.dc.core.auth.BoxPrivilege;
import com.fujitsu.dc.core.auth.Privilege;
import com.fujitsu.dc.core.model.DavCmp;
import com.fujitsu.dc.core.model.DavRsCmp;
import com.fujitsu.dc.core.rs.odata.ODataResource;

/**
 * ODataSvcResourceを担当するJAX-RSリソース.
 */
public final class ODataSvcCollectionResource extends ODataResource {
    DavCmp davCmp;
    // DavCollectionResourceとしての機能を使うためこれをWRAPしておく。
    DavRsCmp davRsCmp;

    /**
     * コンストラクタ.
     * @param parent DavRsCmp
     * @param davCmp DavCmp
     */
    public ODataSvcCollectionResource(final DavRsCmp parent, final DavCmp davCmp) {
        super(parent.getAccessContext(), parent.getUrl() + davCmp.getName() + "/", davCmp.getODataProducer());
        this.davCmp = davCmp;
        this.davRsCmp = new DavRsCmp(parent, davCmp);
    }

    /**
     * PROPFINDの処理.
     * @param requestBodyXml リクエストボディ
     * @param depth Depthヘッダ
     * @param contentLength Content-Length ヘッダ
     * @param transferEncoding Transfer-Encoding ヘッダ
     * @return JAX-RS Response
     */
    @WebDAVMethod.PROPFIND
    public Response propfind(final Reader requestBodyXml,
            @HeaderParam(DcCoreUtils.HttpHeaders.DEPTH) final String depth,
            @HeaderParam(HttpHeaders.CONTENT_LENGTH) final Long contentLength,
            @HeaderParam("Transfer-Encoding") final String transferEncoding) {

        return this.davRsCmp.doPropfind(requestBodyXml, depth, contentLength, transferEncoding,
                BoxPrivilege.READ_PROPERTIES, BoxPrivilege.READ_ACL);

    }

    /**
     * PROPPATCHの処理.
     * @param requestBodyXml リクエストボディ
     * @return JAX-RS Response
     */
    @WebDAVMethod.PROPPATCH
    public Response proppatch(final Reader requestBodyXml) {
        // アクセス制御
        this.checkAccessContext(
                this.davRsCmp.getAccessContext(), BoxPrivilege.WRITE_PROPERTIES);

        return this.davRsCmp.doProppatch(requestBodyXml);
    }

    /**
     * ACLメソッドの処理. ACLの設定を行う.
     * @param reader 設定XML
     * @return JAX-RS Response
     */
    @ACL
    public Response acl(final Reader reader) {
        // アクセス制御
        this.checkAccessContext(this.getAccessContext(), BoxPrivilege.WRITE_ACL);
        return this.davCmp.acl(reader).build();
    }

    /**
     * DELETEメソッドを処理してこのリソースを削除します.
     * @return JAX-RS応答オブジェクト
     */
    @DELETE
    public Response delete() {
        // アクセス制御
        this.checkAccessContext(this.getAccessContext(), BoxPrivilege.WRITE);
        // ODataのスキーマ・データがすでにある場合、処理を失敗させる。
        if (!this.davRsCmp.getDavCmp().isEmpty()) {
            throw DcCoreException.Dav.HAS_CHILDREN;
        }
        return this.davCmp.delete(null).build();
    }

    /**
     * OPTIONSメソッド.
     * @return JAX-RS Response
     */
    @Override
    @OPTIONS
    public Response optionsRoot() {
        // アクセス制御
        this.checkAccessContext(this.getAccessContext(), BoxPrivilege.READ);
        return DcCoreUtils.responseBuilderForOptions(
                HttpMethod.GET,
                HttpMethod.DELETE,
                DcCoreUtils.HttpMethod.PROPFIND,
                DcCoreUtils.HttpMethod.PROPPATCH,
                DcCoreUtils.HttpMethod.ACL
                ).build();
    }

    @Override
    public void checkAccessContext(AccessContext ac, Privilege privilege) {
        this.davRsCmp.checkAccessContext(ac, privilege);
    }

    @Override
    public boolean hasPrivilege(AccessContext ac, Privilege privilege) {
        return this.davRsCmp.hasPrivilege(ac, privilege);
    }

    @Override
    public void checkSchemaAuth(AccessContext ac) {
        ac.checkSchemaAccess(this.davRsCmp.getConfidentialLevel(), this.davRsCmp.getBox());
    }

    /**
     * サービスメタデータリクエストに対応する.
     * @return JAX-RS 応答オブジェクト
     */
    @Path("{first: \\$}metadata")
    public ODataSvcSchemaResource metadata() {
        this.checkAccessContext(this.getAccessContext(), BoxPrivilege.READ);
        return new ODataSvcSchemaResource(this.davRsCmp, this);
    }

    @Override
    public Privilege getNecessaryReadPrivilege(String entitySetNameStr) {
        return BoxPrivilege.READ;
    }

    @Override
    public Privilege getNecessaryWritePrivilege(String entitySetNameStr) {
        return BoxPrivilege.WRITE;
    }

    @Override
    public Privilege getNecessaryOptionsPrivilege() {
        return BoxPrivilege.READ;
    }
}
