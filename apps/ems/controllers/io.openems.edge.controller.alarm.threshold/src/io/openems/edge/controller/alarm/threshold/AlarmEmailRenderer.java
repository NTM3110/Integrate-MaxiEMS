package io.openems.edge.controller.alarm.threshold;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import io.openems.edge.notification.email.EmailMessage;

final class AlarmEmailRenderer {
	private static final String BRAND_COLOR = "#012596";
	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss XXX");

	private AlarmEmailRenderer() {
	}

	public static EmailMessage render(String plantName, List<AlarmTransition> source) {
		var transitions = source.stream().sorted(Comparator.comparing(AlarmTransition::station)
				.thenComparing(AlarmTransition::compartment).thenComparing(AlarmTransition::type)).toList();
		var subject = "Cảnh báo từ " + plantName + " - " + transitions.size() + " sự kiện cảnh báo";
		return new EmailMessage(subject, renderText(plantName, transitions), renderHtml(plantName, transitions));
	}

	private static String renderText(String plantName, List<AlarmTransition> transitions) {
		var text = new StringBuilder() //
				.append("THÔNG BÁO SỰ CỐ\n") //
				.append("Cảnh báo từ ").append(plantName).append(" - ").append(transitions.size()) //
				.append(" sự kiện cảnh báo\n\n");
		for (var index = 0; index < transitions.size(); index++) {
			var transition = transitions.get(index);
			var recovered = transition.newLevel() == 0;
			text.append("Sự kiện ").append(index + 1).append(" - ") //
					.append(recovered ? "ĐÃ PHỤC HỒI" : "CẢNH BÁO").append('\n') //
					.append("Nhà máy: ").append(plantName).append('\n') //
					.append("Sự cố: ").append(displayType(transition)).append('\n') //
					.append("Tên trạm: ").append(transition.station()).append('\n') //
					.append("Khoang: ").append(transition.compartment()).append('\n') //
					.append("Mức cảnh báo: ").append(level(transition)).append('\n') //
					.append("Thời gian nhận sự cố: ").append(TIMESTAMP.format(transition.alarmStartedAt())).append('\n');
			if (transition.deviationValue() != null) {
				text.append("Giá trị sai lệch: ").append(transition.deviationValue()).append('\n');
			}
			if (recovered) {
				text.append("Thời gian phục hồi: ").append(TIMESTAMP.format(transition.timestamp())).append('\n') //
						.append("Sự kiện đã được cảnh báo trong: ") //
						.append(formatDuration(transition.alarmDuration())).append('\n');
			}
			text.append('\n');
		}
		return text.append("Email này được gửi tự động từ hệ thống của ATdigital IoT. ") //
				.append("Vui lòng không trả lại email này.\n").toString();
	}

	private static String renderHtml(String plantName, List<AlarmTransition> transitions) {
		var html = new StringBuilder();
		html.append("<!doctype html><html><body style=\"margin:0;padding:0;background:#eef2ff;") //
				.append("font-family:Arial,Helvetica,sans-serif;color:#172033;\">") //
				.append("<div style=\"display:none;max-height:0;overflow:hidden;color:transparent;\">") //
				.append("Thông báo sự cố từ ").append(escape(plantName)).append("</div>") //
				.append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\"") //
				.append(" style=\"background:#eef2ff;\"><tr><td align=\"center\" style=\"padding:28px 12px;\">") //
				.append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\"") //
				.append(" style=\"max-width:640px;background:#ffffff;border:1px solid #dbe2f0;\">") //
				.append("<tr><td style=\"padding:22px 28px;background:").append(BRAND_COLOR) //
				.append(";color:#ffffff;font-size:22px;font-weight:bold;\">ATdigital IoT</td></tr>") //
				.append("<tr><td style=\"padding:28px 28px 18px;\">") //
				.append("<div style=\"font-size:12px;font-weight:bold;letter-spacing:1px;color:") //
				.append(BRAND_COLOR).append(";\">THÔNG BÁO SỰ CỐ</div>") //
				.append("<h1 style=\"margin:8px 0 8px;font-size:23px;line-height:1.35;color:#172033;\">") //
				.append("Cảnh báo từ ").append(escape(plantName)).append("</h1>") //
				.append("<p style=\"margin:0;font-size:14px;line-height:1.6;color:#526078;\">") //
				.append("Hệ thống ghi nhận ").append(transitions.size()).append(" sự kiện cảnh báo cần được theo dõi.") //
				.append("</p></td></tr>");

		for (var index = 0; index < transitions.size(); index++) {
			appendEvent(html, plantName, transitions.get(index), index + 1);
		}

		html.append("<tr><td style=\"padding:20px 28px;border-top:1px solid #dbe2f0;") //
				.append("font-size:12px;line-height:1.6;color:#667085;\">") //
				.append("Email này được gửi tự động từ hệ thống của ATdigital IoT. ") //
				.append("Vui lòng không trả lại email này.") //
				.append("</td></tr></table></td></tr></table></body></html>");
		return html.toString();
	}

	private static void appendEvent(StringBuilder html, String plantName, AlarmTransition transition, int index) {
		var recovered = transition.newLevel() == 0;
		html.append("<tr><td style=\"padding:0 28px 24px;\">") //
				.append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\"") //
				.append(" style=\"border-top:2px solid ").append(BRAND_COLOR).append(";\">") //
				.append("<tr><td colspan=\"2\" style=\"padding:14px 0 8px;font-size:13px;font-weight:bold;color:") //
				.append(BRAND_COLOR).append(";\">SỰ KIỆN ").append(index).append(" · ") //
				.append(recovered ? "ĐÃ PHỤC HỒI" : "CẢNH BÁO").append("</td></tr>") //
				.append(detailRow("Nhà máy", plantName)) //
				.append(detailRow("Sự cố", displayType(transition))) //
				.append(detailRow("Tên trạm", transition.station())) //
				.append(detailRow("Khoang", transition.compartment().name())) //
				.append(detailRow("Mức cảnh báo", level(transition))) //
				.append(detailRow("Thời gian nhận sự cố", TIMESTAMP.format(transition.alarmStartedAt())));
		if (transition.deviationValue() != null) {
			html.append(detailRow("Giá trị sai lệch", transition.deviationValue()));
		}
		if (recovered) {
			html.append(detailRow("Thời gian phục hồi", TIMESTAMP.format(transition.timestamp()))) //
					.append(detailRow("Thời gian đã cảnh báo", formatDuration(transition.alarmDuration())));
		}
		html.append("</table></td></tr>");
	}

	private static String detailRow(String label, String value) {
		return new StringBuilder("<tr><td valign=\"top\" style=\"width:180px;padding:7px 12px 7px 0;") //
				.append("font-size:13px;color:#667085;\">").append(label) //
				.append("</td><td valign=\"top\" style=\"padding:7px 0;font-size:13px;color:#172033;font-weight:bold;\">") //
				.append(escape(value)).append("</td></tr>").toString();
	}

	private static String displayType(AlarmTransition transition) {
		var value = transition.type().displayName();
		return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
	}

	private static String level(AlarmTransition transition) {
		var alarmLevel = transition.newLevel() == 0 ? transition.oldLevel() : transition.newLevel();
		return transition.type().levelName(alarmLevel).toUpperCase(Locale.ROOT);
	}

	private static String formatDuration(Duration duration) {
		if (duration == null || duration.isNegative()) {
			return "Không xác định";
		}
		var totalSeconds = duration.getSeconds();
		var days = totalSeconds / 86_400;
		var hours = totalSeconds % 86_400 / 3_600;
		var minutes = totalSeconds % 3_600 / 60;
		var seconds = totalSeconds % 60;
		var result = new StringBuilder();
		appendDurationPart(result, days, "ngày");
		appendDurationPart(result, hours, "giờ");
		appendDurationPart(result, minutes, "phút");
		if (seconds > 0) {
			appendDurationPart(result, seconds, "giây");
		}
		return result.length() == 0 ? "0 giây" : result.toString();
	}

	private static void appendDurationPart(StringBuilder target, long value, String unit) {
		if (value == 0) {
			return;
		}
		if (target.length() > 0) {
			target.append(' ');
		}
		target.append(value).append(' ').append(unit);
	}

	private static String escape(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
				.replace("'", "&#39;");
	}
}
