package com.example.pictgram.controller;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.io.FilenameUtils;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.Base64Utils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.pictgram.entity.Favorite;
import com.example.pictgram.entity.Topic;
import com.example.pictgram.entity.UserInf;
import com.example.pictgram.form.FavoriteForm;
import com.example.pictgram.form.TopicForm;
import com.example.pictgram.form.UserForm;
import com.example.pictgram.repository.TopicRepository;

import jakarta.servlet.http.HttpServletRequest;

@Controller
public class TopicsController {

	@Autowired
	private MessageSource messageSource;

	@Autowired
	private ModelMapper modelMapper;
	@Autowired
	private TopicRepository repository;
	@Autowired
	private HttpServletRequest request;

	@Value("${image.local:false}")
	private String imageLocal;

	@GetMapping("/topics")
	public String index(Principal principal, Model model) throws IOException {
		Authentication authentication = (Authentication) principal;
		UserInf user = (UserInf) authentication.getPrincipal();

		List<Topic> topics = repository.findAllByOrderByUpdatedAtDesc();
		List<TopicForm> list = new ArrayList<>();
		for (Topic entity : topics) {
			TopicForm form = getTopic(user, entity);
			list.add(form);
		}
		model.addAttribute("list", list);

		return "topics/index";
	}

	public TopicForm getTopic(UserInf user, Topic entity) throws FileNotFoundException, IOException {
		modelMapper.getConfiguration().setAmbiguityIgnored(true);
		modelMapper.typeMap(Topic.class, TopicForm.class).addMappings(mapper -> mapper.skip(TopicForm::setUser));

		modelMapper.typeMap(Topic.class, TopicForm.class).addMappings(mapper -> mapper.skip(TopicForm::setFavorites));
		modelMapper.typeMap(Favorite.class, FavoriteForm.class)
				.addMappings(mapper -> mapper.skip(FavoriteForm::setTopic));

		boolean isImageLocal = false;
		if (imageLocal != null) {
			isImageLocal = new Boolean(imageLocal);
		}
		TopicForm form = modelMapper.map(entity, TopicForm.class);

		if (isImageLocal) {
			try (InputStream is = new FileInputStream(new File(entity.getPath()));
					ByteArrayOutputStream os = new ByteArrayOutputStream()) {
				byte[] indata = new byte[10240 * 16];
				int size;
				while ((size = is.read(indata, 0, indata.length)) > 0) {
					os.write(indata, 0, size);
				}
				StringBuilder data = new StringBuilder();
				data.append("data:");
				data.append(getMimeType(entity.getPath()));
				data.append(";base64,");

				data.append(new String(Base64Utils.encode(os.toByteArray()), "ASCII"));
				form.setImageData(data.toString());
			}
		}

		UserForm userForm = modelMapper.map(entity.getUser(), UserForm.class);
		form.setUser(userForm);

		List<FavoriteForm> favorites = new ArrayList<FavoriteForm>();
		for (Favorite favoriteEntity : entity.getFavorites()) {
			FavoriteForm favorite = modelMapper.map(favoriteEntity, FavoriteForm.class);
			favorites.add(favorite);
			if (user.getUserId().equals(favoriteEntity.getUserId())) {
				form.setFavorite(favorite);
			}
		}
		form.setFavorites(favorites);

		return form;
	}

	private String getMimeType(String path) {
		String extension = FilenameUtils.getExtension(path);
		String mimeType = "image/";
		switch (extension) {
		case "jpg":
		case "jpeg":
			mimeType += "jpeg";
			break;
		case "png":
			mimeType += "png";
			break;
		case "gif":
			mimeType += "gif";
			break;
		}
		return mimeType;
	}

	@GetMapping("/topics/new")
	public String newTopic(Model model) {
		model.addAttribute("form", new TopicForm());
		return "topics/new";
	}

	@PostMapping("/topic")
	//	public String create(Principal principal, @Validated @ModelAttribute("form") TopicForm form, BindingResult result,
	//			Model model, @RequestParam MultipartFile image, RedirectAttributes redirAttrs)
	//			throws IOException {
	public String create(Principal principal, @Validated @ModelAttribute("form") TopicForm form, BindingResult result,
			Model model, @RequestParam MultipartFile image, RedirectAttributes redirAttrs, Locale locale)
			throws IOException {
		if (result.hasErrors()) {
			model.addAttribute("hasMessage", true);
			model.addAttribute("class", "alert-danger");
			//			model.addAttribute("message", "投稿に失敗しました。");
			model.addAttribute("message", messageSource.getMessage("topics.create.flash.1", new String[] {}, locale));
			return "topics/new";
		}

		boolean isImageLocal = false;
		if (imageLocal != null) {
			isImageLocal = new Boolean(imageLocal);
		}

		Topic entity = new Topic();
		Authentication authentication = (Authentication) principal;
		UserInf user = (UserInf) authentication.getPrincipal();
		entity.setUserId(user.getUserId());
		File destFile = null;
		if (isImageLocal) {
			destFile = saveImageLocal(image, entity);
			entity.setPath(destFile.getAbsolutePath());
		} else {
			entity.setPath("");
		}
		entity.setDescription(form.getDescription());
		repository.saveAndFlush(entity);

		redirAttrs.addFlashAttribute("hasMessage", true);
		redirAttrs.addFlashAttribute("class", "alert-info");
		//		redirAttrs.addFlashAttribute("message", "投稿に成功しました。");
		redirAttrs.addFlashAttribute("message",
				messageSource.getMessage("topics.create.flash.2", new String[] {}, locale));

		return "redirect:/topics";
	}

	private File saveImageLocal(MultipartFile image, Topic entity) throws IOException {
		File uploadDir = new File("/uploads");
		uploadDir.mkdir();

		String uploadsDir = "/uploads/";
		String realPathToUploads = request.getServletContext().getRealPath(uploadsDir);
		if (!new File(realPathToUploads).exists()) {
			new File(realPathToUploads).mkdir();
		}
		String fileName = image.getOriginalFilename();
		File destFile = new File(realPathToUploads, fileName);
		image.transferTo(destFile);

		return destFile;
	}

}