package org.jocean.http;

import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@Produces({MediaType.APPLICATION_XML})
@JacksonXmlRootElement(localName="xml")
@Path("https://api.mch.weixin.qq.com/mmpaymkttransfers/sendredpack")
public class SendRedpackRequest {
	private String nonce_str;
	private String sign;
	private String mch_billno;
	private String mch_id;
	private String wxappid;
	private String send_name;
	private String re_openid;
	private int total_amount;
	private int total_num;
	private String wishing;
	private String client_ip;
	private String act_name;
	private String remark;
	private String scene_id;
	private String risk_info;
	private String consume_mch_id;
	
	@JacksonXmlProperty(localName="nonce_str")
	public String getNonceStr() {
		return nonce_str;
	}
	@JacksonXmlProperty(localName="nonce_str")
	public void setNonceStr(String nonce_str) {
		this.nonce_str = nonce_str;
	}
	@JacksonXmlProperty(localName="sign")
	public String getSign() {
		return sign;
	}
	@JacksonXmlProperty(localName="sign")
	public void setSign(String sign) {
		this.sign = sign;
	}
	@JacksonXmlProperty(localName="mch_billno")
	public String getMchBillno() {
		return mch_billno;
	}
	@JacksonXmlProperty(localName="mch_billno")
	public void setMchBillno(String mch_billno) {
		this.mch_billno = mch_billno;
	}
	@JacksonXmlProperty(localName="mch_id")
	public String getMchId() {
		return mch_id;
	}
	@JacksonXmlProperty(localName="mch_id")
	public void setMchId(String mch_id) {
		this.mch_id = mch_id;
	}
	@JacksonXmlProperty(localName="wxappid")
	public String getWxappid() {
		return wxappid;
	}
	@JacksonXmlProperty(localName="wxappid")
	public void setWxappid(String wxappid) {
		this.wxappid = wxappid;
	}
	@JacksonXmlProperty(localName="send_name")
	public String getSendName() {
		return send_name;
	}
	@JacksonXmlProperty(localName="send_name")
	public void setSendName(String send_name) {
		this.send_name = send_name;
	}
	@JacksonXmlProperty(localName="re_openid")
	public String getReOpenid() {
		return re_openid;
	}
	@JacksonXmlProperty(localName="re_openid")
	public void setReOpenid(String re_openid) {
		this.re_openid = re_openid;
	}
	@JacksonXmlProperty(localName="total_amount")
	public int getTotalAmount() {
		return total_amount;
	}
	@JacksonXmlProperty(localName="total_amount")
	public void setTotalAmount(int total_amount) {
		this.total_amount = total_amount;
	}
	@JacksonXmlProperty(localName="total_num")
	public int getTotalNum() {
		return total_num;
	}
	@JacksonXmlProperty(localName="total_num")
	public void setTotalNum(int total_num) {
		this.total_num = total_num;
	}
	@JacksonXmlProperty(localName="wishing")
	public String getWishing() {
		return wishing;
	}
	@JacksonXmlProperty(localName="wishing")
	public void setWishing(String wishing) {
		this.wishing = wishing;
	}
	@JacksonXmlProperty(localName="client_ip")
	public String getClientIp() {
		return client_ip;
	}
	@JacksonXmlProperty(localName="client_ip")
	public void setClientIp(String client_ip) {
		this.client_ip = client_ip;
	}
	@JacksonXmlProperty(localName="act_name")
	public String getActName() {
		return act_name;
	}
	@JacksonXmlProperty(localName="act_name")
	public void setActName(String act_name) {
		this.act_name = act_name;
	}
	@JacksonXmlProperty(localName="remark")
	public String getRemark() {
		return remark;
	}
	@JacksonXmlProperty(localName="remark")
	public void setRemark(String remark) {
		this.remark = remark;
	}
	@JacksonXmlProperty(localName="scene_id")
	public String getSceneId() {
		return scene_id;
	}
	@JacksonXmlProperty(localName="scene_id")
	public void setSceneId(String scene_id) {
		this.scene_id = scene_id;
	}
	@JacksonXmlProperty(localName="risk_info")
	public String getRiskInfo() {
		return risk_info;
	}
	@JacksonXmlProperty(localName="risk_info")
	public void setRiskInfo(String risk_info) {
		this.risk_info = risk_info;
	}
	@JacksonXmlProperty(localName="consume_mch_id")
	public String getConsumeMchId() {
		return consume_mch_id;
	}
	@JacksonXmlProperty(localName="consume_mch_id")
	public void setConsumeMchId(final String consume_mch_id) {
		this.consume_mch_id = consume_mch_id;
	}
    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("SendRedpackRequest [nonce_str=").append(nonce_str).append(", sign=").append(sign)
                .append(", mch_billno=").append(mch_billno).append(", mch_id=").append(mch_id).append(", wxappid=")
                .append(wxappid).append(", send_name=").append(send_name).append(", re_openid=").append(re_openid)
                .append(", total_amount=").append(total_amount).append(", total_num=").append(total_num)
                .append(", wishing=").append(wishing).append(", client_ip=").append(client_ip).append(", act_name=")
                .append(act_name).append(", remark=").append(remark).append(", scene_id=").append(scene_id)
                .append(", risk_info=").append(risk_info).append(", consume_mch_id=").append(consume_mch_id)
                .append("]");
        return builder.toString();
    }
}
